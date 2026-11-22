import { Router } from 'express';
import { z } from 'zod';
import { query, withTransaction, writeAuditLog } from '../config/database.js';
import { env } from '../config/env.js';
import { redis, RedisKeys } from '../config/redis.js';
import {
  checkMaintenanceMode,
  rateLimit,
  requireAuth,
  signStreamToken,
} from '../middleware/auth.js';
import { calculateCoinsForHours, getPlatformCoinConfig } from '../services/coinService.js';
import { selectBestGpuNode, settleSession } from '../services/serverAllocator.js';
import { notifySessionTerminated } from '../services/signalingService.js';

const router = Router();

const startSessionSchema = z.object({
  game_id: z.string().uuid(),
  duration_hours: z.number().int().min(1).max(6),
  resolution: z.enum(['720p', '1080p']).default('720p'),
  target_fps: z.union([z.literal(30), z.literal(60), z.literal(120)]).default(60),
  codec: z.enum(['H264', 'H265']).default('H265'),
});

router.post('/start', requireAuth, checkMaintenanceMode, rateLimit('start_session', 10, 60), async (req, res) => {
  const parsed = startSessionSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid session configuration.' });
    return;
  }

  const userId = req.authUser!.userId;
  const { game_id, duration_hours, resolution, target_fps, codec } = parsed.data;

  try {
    const started = await withTransaction(async (client) => {
      const gameRes = await client.query<{
        id: string;
        title: string;
        is_licensed: boolean;
        is_available: boolean;
      }>(`SELECT id, title, is_licensed, is_available FROM games WHERE id = $1`, [game_id]);
      const game = gameRes.rows[0];
      if (!game || !game.is_licensed || !game.is_available) {
        return { errorStatus: 403, error: 'Requested game is unavailable or unlicensed.' };
      }

      const activeExisting = await client.query(
        `SELECT id FROM gaming_sessions WHERE user_id = $1 AND status IN ('active', 'provisioning')`,
        [userId]
      );
      if (activeExisting.rowCount && activeExisting.rowCount > 0) {
        return { errorStatus: 409, error: 'You already have an active gaming session.' };
      }

      const { coinsPerHour } = await getPlatformCoinConfig(client);
      const coinsRequired = calculateCoinsForHours(duration_hours, coinsPerHour);

      const userRes = await client.query<{ coin_balance: number }>(
        `SELECT coin_balance FROM users WHERE id = $1 FOR UPDATE`,
        [userId]
      );
      const balance = userRes.rows[0]?.coin_balance ?? 0;
      if (balance < coinsRequired) {
        return {
          errorStatus: 402,
          error: `Insufficient coins. ${duration_hours}h requires ${coinsRequired} coins (Balance: ${balance}).`,
        };
      }

      const gpuNode = await selectBestGpuNode(client, game_id);
      if (!gpuNode) {
        return {
          errorStatus: 503,
          error: 'No online GPU cloud nodes have capacity for this game right now.',
        };
      }

      const newBalance = balance - coinsRequired;
      await client.query(`UPDATE users SET coin_balance = $1, updated_at = NOW() WHERE id = $2`, [
        newBalance,
        userId,
      ]);

      await client.query(
        `UPDATE gpu_servers SET active_sessions = active_sessions + 1 WHERE id = $1`,
        [gpuNode.id]
      );

      const durationMinutes = duration_hours * 60;
      const expiresAt = new Date(Date.now() + durationMinutes * 60_000);
      const sessInsert = await client.query<{ id: string }>(
        `INSERT INTO gaming_sessions (
           user_id, game_id, server_id, status, resolution, target_fps, codec,
           duration_minutes, coins_reserved, coins_charged, expires_at
         ) VALUES ($1, $2, $3, 'active', $4, $5, $6, $7, $8, $8, $9)
         RETURNING id`,
        [
          userId,
          game.id,
          gpuNode.id,
          resolution,
          target_fps,
          codec,
          durationMinutes,
          coinsRequired,
          expiresAt,
        ]
      );
      const sessionId = sessInsert.rows[0].id;

      await client.query(
        `INSERT INTO coin_transactions (user_id, session_id, amount, type, description, balance_after)
         VALUES ($1, $2, $3, 'session_reserve', $4, $5)`,
        [
          userId,
          sessionId,
          -coinsRequired,
          `Reserved ${duration_hours}h for ${game.title}`,
          newBalance,
        ]
      );

      return {
        sessionId,
        gameTitle: game.title,
        gpuNode,
        coinsRequired,
        newBalance,
        durationMinutes,
        expiresAt: expiresAt.toISOString(),
      };
    });

    if ('error' in started) {
      res.status(started.errorStatus || 400).json({ success: false, error: started.error });
      return;
    }

    const streamToken = signStreamToken(started.sessionId, userId, started.gpuNode.id);
    await redis
      .set(
        RedisKeys.sessionState(started.sessionId),
        JSON.stringify({
          userId,
          gameId: game_id,
          serverId: started.gpuNode.id,
          resolution,
          target_fps,
          codec,
        }),
        'EX',
        started.durationMinutes * 60 + 300
      )
      .catch(() => {});

    await writeAuditLog(userId, 'SESSION_STARTED', 'gaming_session', started.sessionId, {
      game_id,
      server_id: started.gpuNode.id,
      coins_reserved: started.coinsRequired,
    }, req.ip);

    const iceServers: Array<{ urls: string[]; username?: string; credential?: string }> = [
      { urls: [env.stunUrl] },
    ];
    if (env.turnUrl) {
      iceServers.push({
        urls: [env.turnUrl],
        username: env.turnUsername,
        credential: env.turnCredential,
      });
    }

    res.status(201).json({
      success: true,
      data: {
        session_id: started.sessionId,
        game_id,
        game_title: started.gameTitle,
        server_id: started.gpuNode.id,
        server_name: started.gpuNode.name,
        server_region: started.gpuNode.region,
        gpu_model: started.gpuNode.gpu_model,
        signaling_ws_url: `${env.publicWsBaseUrl}/ws/signaling`,
        stream_token: streamToken,
        ice_servers: iceServers,
        coins_reserved: started.coinsRequired,
        new_balance: started.newBalance,
        duration_minutes: started.durationMinutes,
        expires_at: started.expiresAt,
      },
    });
  } catch (err) {
    console.error(err);
    res.status(500).json({ success: false, error: 'Failed to allocate cloud gaming session.' });
  }
});

router.post('/:id/end', requireAuth, async (req, res) => {
  const sessionId = String(req.params.id);
  const sessionRes = await query<{ user_id: string }>(
    `SELECT user_id FROM gaming_sessions WHERE id = $1`,
    [sessionId]
  );
  if (!sessionRes.rows[0]) {
    res.status(404).json({ success: false, error: 'Session not found.' });
    return;
  }
  if (sessionRes.rows[0].user_id !== req.authUser!.userId && req.authUser!.role !== 'admin') {
    res.status(403).json({ success: false, error: 'Forbidden.' });
    return;
  }

  const settled = await settleSession(sessionId, 'ended');
  notifySessionTerminated(sessionId);
  await writeAuditLog(req.authUser!.userId, 'SESSION_ENDED', 'gaming_session', sessionId, settled, req.ip);

  res.json({
    success: true,
    data: {
      session_id: settled.sessionId,
      minutes_used: settled.minutesUsed,
      coins_charged: settled.coinsCharged,
      coins_refunded: settled.coinsRefunded,
      new_balance: settled.newBalance,
    },
  });
});

router.get('/history', requireAuth, async (req, res) => {
  const rows = await query(
    `SELECT gs.id, gs.game_id, g.title AS game_title, srv.name AS server_name, srv.gpu_model,
            gs.status, gs.resolution, gs.target_fps, gs.codec, gs.coins_charged,
            gs.started_at, gs.ended_at, gs.avg_latency_ms
     FROM gaming_sessions gs
     INNER JOIN games g ON g.id = gs.game_id
     INNER JOIN gpu_servers srv ON srv.id = gs.server_id
     WHERE gs.user_id = $1
     ORDER BY gs.started_at DESC
     LIMIT 50`,
    [req.authUser!.userId]
  );
  res.json({ success: true, data: rows.rows });
});

export default router;
