import { PoolClient } from 'pg';
import { query, withTransaction, writeAuditLog } from '../config/database.js';

export const DEFAULT_DAILY_REWARD_COINS = 20;
export const DEFAULT_COINS_PER_HOUR = 10;

export function calculateCoinsForHours(durationHours: number, coinsPerHour = DEFAULT_COINS_PER_HOUR): number {
  if (!Number.isInteger(durationHours) || durationHours < 1 || durationHours > 6) {
    throw new Error('Duration must be between 1 and 6 whole hours.');
  }
  return durationHours * coinsPerHour;
}

export function calculateUsedCoins(minutesUsed: number, coinsReserved: number, coinsPerHour = DEFAULT_COINS_PER_HOUR): {
  coinsCharged: number;
  coinsRefunded: number;
} {
  // Bill per started 6-minute block (1 coin = 6 minutes at 10 coins/hour), capped at reserved coins
  const safeMinutes = Math.max(0, minutesUsed);
  const blocks = Math.ceil((safeMinutes * coinsPerHour) / 60);
  const coinsCharged = Math.min(coinsReserved, Math.max(1, blocks));
  const coinsRefunded = Math.max(0, coinsReserved - coinsCharged);
  return { coinsCharged, coinsRefunded };
}

export async function getPlatformCoinConfig(client?: PoolClient): Promise<{
  dailyRewardCoins: number;
  coinsPerHour: number;
}> {
  const exec = client ? client.query.bind(client) : query;
  const res = await exec<{ key: string; value: string }>(
    `SELECT key, value FROM platform_settings WHERE key IN ('daily_reward_coins', 'coins_per_hour')`
  );
  let dailyRewardCoins = DEFAULT_DAILY_REWARD_COINS;
  let coinsPerHour = DEFAULT_COINS_PER_HOUR;
  for (const row of res.rows) {
    if (row.key === 'daily_reward_coins') dailyRewardCoins = Number(row.value) || DEFAULT_DAILY_REWARD_COINS;
    if (row.key === 'coins_per_hour') coinsPerHour = Number(row.value) || DEFAULT_COINS_PER_HOUR;
  }
  return { dailyRewardCoins, coinsPerHour };
}

export async function claimDailyRewardForUser(userId: string, ip?: string) {
  return withTransaction(async (client) => {
    const config = await getPlatformCoinConfig(client);
    const utcDate = new Date().toISOString().slice(0, 10);

    const existing = await client.query(
      `SELECT id FROM daily_reward_claims WHERE user_id = $1 AND claim_date = $2`,
      [userId, utcDate]
    );
    if (existing.rowCount && existing.rowCount > 0) {
      return {
        alreadyClaimed: true,
        coinsCredited: 0,
        newBalance: null,
      };
    }

    const userRes = await client.query<{ coin_balance: number }>(
      `SELECT coin_balance FROM users WHERE id = $1 FOR UPDATE`,
      [userId]
    );
    if (!userRes.rows[0]) {
      throw new Error('User account not found');
    }

    const newBalance = userRes.rows[0].coin_balance + config.dailyRewardCoins;
    await client.query(`UPDATE users SET coin_balance = $1, updated_at = NOW() WHERE id = $2`, [
      newBalance,
      userId,
    ]);

    await client.query(
      `INSERT INTO daily_reward_claims (user_id, claim_date, coins_awarded) VALUES ($1, $2, $3)`,
      [userId, utcDate, config.dailyRewardCoins]
    );

    await client.query(
      `INSERT INTO coin_transactions (user_id, amount, type, description, balance_after)
       VALUES ($1, $2, 'daily_reward', $3, $4)`,
      [userId, config.dailyRewardCoins, `Daily free reward (${utcDate})`, newBalance]
    );

    await writeAuditLog(userId, 'DAILY_REWARD_CLAIMED', 'user', userId, {
      coins: config.dailyRewardCoins,
      claimDate: utcDate,
    }, ip);

    return {
      alreadyClaimed: false,
      coinsCredited: config.dailyRewardCoins,
      newBalance,
    };
  });
}

export async function adjustCoinsByAdmin(
  adminId: string,
  targetUserId: string,
  amountDelta: number,
  reason: string,
  ip?: string
) {
  if (!Number.isInteger(amountDelta) || amountDelta === 0) {
    throw new Error('Adjustment amount must be a non-zero integer.');
  }
  return withTransaction(async (client) => {
    const userRes = await client.query<{ coin_balance: number }>(
      `SELECT coin_balance FROM users WHERE id = $1 FOR UPDATE`,
      [targetUserId]
    );
    if (!userRes.rows[0]) {
      throw new Error('Target user not found');
    }
    const newBalance = userRes.rows[0].coin_balance + amountDelta;
    if (newBalance < 0) {
      throw new Error('Coin adjustment would result in a negative balance.');
    }

    await client.query(`UPDATE users SET coin_balance = $1, updated_at = NOW() WHERE id = $2`, [
      newBalance,
      targetUserId,
    ]);

    const txType = amountDelta > 0 ? 'admin_grant' : 'admin_deduct';
    await client.query(
      `INSERT INTO coin_transactions (user_id, amount, type, description, balance_after)
       VALUES ($1, $2, $3, $4, $5)`,
      [targetUserId, amountDelta, txType, reason, newBalance]
    );

    await writeAuditLog(adminId, 'ADMIN_COIN_ADJUSTMENT', 'user', targetUserId, {
      amountDelta,
      reason,
      newBalance,
    }, ip);

    return { newBalance };
  });
}
