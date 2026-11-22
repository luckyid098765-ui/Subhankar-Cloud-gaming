import { Router } from 'express';
import { query } from '../config/database.js';
import { checkMaintenanceMode, rateLimit, requireAuth } from '../middleware/auth.js';
import { claimDailyRewardForUser, getPlatformCoinConfig } from '../services/coinService.js';

const router = Router();

router.get('/', requireAuth, async (req, res) => {
  const userId = req.authUser!.userId;
  const config = await getPlatformCoinConfig();
  const utcDate = new Date().toISOString().slice(0, 10);

  const [userRes, claimRes, txRes] = await Promise.all([
    query<{ coin_balance: number }>(`SELECT coin_balance FROM users WHERE id = $1`, [userId]),
    query(`SELECT id FROM daily_reward_claims WHERE user_id = $1 AND claim_date = $2`, [userId, utcDate]),
    query(
      `SELECT id, amount, type, description, balance_after, created_at
       FROM coin_transactions WHERE user_id = $1
       ORDER BY created_at DESC LIMIT 50`,
      [userId]
    ),
  ]);

  const balance = userRes.rows[0]?.coin_balance ?? 0;
  const nextUtcMidnight = new Date(Date.UTC(
    new Date().getUTCFullYear(),
    new Date().getUTCMonth(),
    new Date().getUTCDate() + 1,
    0, 0, 0
  )).toISOString();

  res.json({
    success: true,
    data: {
      balance,
      hourly_rate_coins: config.coinsPerHour,
      daily_reward_coins: config.dailyRewardCoins,
      daily_reward_claimed_today: Boolean(claimRes.rowCount && claimRes.rowCount > 0),
      next_claim_utc: nextUtcMidnight,
      transactions: txRes.rows,
    },
  });
});

router.post('/daily-reward', requireAuth, checkMaintenanceMode, rateLimit('daily_reward', 5, 60), async (req, res) => {
  const result = await claimDailyRewardForUser(req.authUser!.userId, req.ip);
  if (result.alreadyClaimed) {
    res.status(409).json({
      success: false,
      error: 'Daily 20-coin reward already claimed today (UTC).',
    });
    return;
  }
  res.json({
    success: true,
    data: {
      claimed: true,
      coins_credited: result.coinsCredited,
      new_balance: result.newBalance,
      message: `Credited ${result.coinsCredited} daily coins.`,
    },
  });
});

export default router;
