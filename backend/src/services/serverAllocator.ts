import { PoolClient } from 'pg';
import { query, withTransaction, writeAuditLog } from '../config/database.js';
import { redis, RedisKeys } from '../config/redis.js';
import { calculateUsedCoins, getPlatformCoinConfig } from './coinService.js';

export interface AllocatedGpuServer {
  id: string;
  name: string;
  region: string;
  gpu_model: string;
  signaling_endpoint: string;
}

export async function selectBestGpuNode(
  client: PoolClient,
  gameId: string
): Promise<AllocatedGpuServer | null> {
  const res = await client.query<AllocatedGpuServer>(
    `SELECT s.id, s.name, s.region, s.gpu_model, s.signaling_endpoint
     FROM gpu_servers s
     INNER JOIN server_installed_games sig ON sig.server_id = s.id AND sig.game_id = $1
     WHERE s.status = 'online'
       AND sig.install_verified = TRUE
       AND s.active_sessions < s.max_sessions
       AND (s.last_heartbeat_at IS NULL OR s.last_heartbeat_at > NOW() - INTERVAL '90 seconds')
     ORDER BY (s.active_sessions::float / s.max_sessions::float) ASC, s.gpu_utilization_pct ASC
     LIMIT 1
     FOR UPDATE OF s SKIP LOCKED`,
    [gameId]
  );
  return res.rows[0] || null;
}

export async function runSessionCleanupAndHealthMonitor(): Promise<void> {
  // 1. Mark stale GPU servers offline if heartbeat missed for > 90s
  const staleServers = await query<{ id: string; name: string }>(
    `UPDATE gpu_servers
     SET status = 'offline'
     WHERE status = 'online'
       AND last_heartbeat_at IS NOT NULL
       AND last_heartbeat_at < NOW() - INTERVAL '90 seconds'
     RETURNING id, name`
  );

  for (const srv of staleServers.rows) {
    await writeAuditLog(null, 'GPU_NODE_HEARTBEAT_TIMEOUT', 'gpu_server', srv.id, {
      name: srv.name,
    });
  }

  // 2. Expire overdue sessions and reconcile coins atomically
  const expired = await query<{
    id: string;
    user_id: string;
    server_id: string;
    coins_reserved: number;
    started_at: string;
  }>(
    `SELECT id, user_id, server_id, coins_reserved, started_at
     FROM gaming_sessions
     WHERE status = 'active' AND expires_at <= NOW()`
  );

  for (const s of expired.rows) {
    await settleSession(s.id, 'expired');
  }
}

export async function settleSession(
  sessionId: string,
  targetStatus: 'ended' | 'expired' | 'failed'
): Promise<{
  sessionId: string;
  minutesUsed: number;
  coinsCharged: number;
  coinsRefunded: number;
  newBalance: number;
}> {
  return withTransaction(async (client) => {
    const sessRes = await client.query<{
      id: string;
      user_id: string;
      server_id: string;
      status: string;
      coins_reserved: number;
      started_at: Date;
    }>(
      `SELECT id, user_id, server_id, status, coins_reserved, started_at
       FROM gaming_sessions
       WHERE id = $1
       FOR UPDATE`,
      [sessionId]
    );
    const session = sessRes.rows[0];
    if (!session) {
      throw new Error('Gaming session not found');
    }
    if (session.status !== 'active' && session.status !== 'provisioning') {
      const balRes = await client.query<{ coin_balance: number }>(
        `SELECT coin_balance FROM users WHERE id = $1`,
        [session.user_id]
      );
      return {
        sessionId,
        minutesUsed: 0,
        coinsCharged: 0,
        coinsRefunded: 0,
        newBalance: balRes.rows[0]?.coin_balance || 0,
      };
    }

    const elapsedMs = Math.max(0, Date.now() - new Date(session.started_at).getTime());
    const minutesUsed = Math.max(1, Math.ceil(elapsedMs / 60_000));
    const { coinsPerHour } = await getPlatformCoinConfig(client);

    const { coinsCharged, coinsRefunded } =
      targetStatus === 'failed'
        ? { coinsCharged: 0, coinsRefunded: session.coins_reserved }
        : calculateUsedCoins(minutesUsed, session.coins_reserved, coinsPerHour);

    await client.query(
      `UPDATE gaming_sessions
       SET status = $1, coins_charged = $2, ended_at = NOW()
       WHERE id = $3`,
      [targetStatus, coinsCharged, sessionId]
    );

    await client.query(
      `UPDATE gpu_servers
       SET active_sessions = GREATEST(0, active_sessions - 1)
       WHERE id = $1`,
      [session.server_id]
    );

    const userRes = await client.query<{ coin_balance: number }>(
      `SELECT coin_balance FROM users WHERE id = $1 FOR UPDATE`,
      [session.user_id]
    );
    let newBalance = userRes.rows[0]?.coin_balance || 0;

    if (coinsRefunded > 0) {
      newBalance += coinsRefunded;
      await client.query(`UPDATE users SET coin_balance = $1, updated_at = NOW() WHERE id = $2`, [
        newBalance,
        session.user_id,
      ]);
      await client.query(
        `INSERT INTO coin_transactions (user_id, session_id, amount, type, description, balance_after)
         VALUES ($1, $2, $3, 'session_refund', $4, $5)`,
        [
          session.user_id,
          sessionId,
          coinsRefunded,
          `Unused time refund (${targetStatus})`,
          newBalance,
        ]
      );
    }

    await redis.del(RedisKeys.sessionState(sessionId)).catch(() => {});

    return {
      sessionId,
      minutesUsed,
      coinsCharged,
      coinsRefunded,
      newBalance,
    };
  });
}
