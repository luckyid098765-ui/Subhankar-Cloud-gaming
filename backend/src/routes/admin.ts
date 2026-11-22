import { Router } from 'express';
import { z } from 'zod';
import { query, writeAuditLog } from '../config/database.js';
import { redis, RedisKeys } from '../config/redis.js';
import { requireAdmin, requireAuth } from '../middleware/auth.js';
import { adjustCoinsByAdmin } from '../services/coinService.js';
import { settleSession } from '../services/serverAllocator.js';
import { notifySessionTerminated } from '../services/signalingService.js';

const router = Router();
router.use(requireAuth, requireAdmin);

router.get('/analytics', async (_req, res) => {
  const [users, activeSessions, onlineServers, coinsIssued, totalGames, openTickets] = await Promise.all([
    query<{ count: string }>(`SELECT COUNT(*)::text AS count FROM users`),
    query<{ count: string }>(`SELECT COUNT(*)::text AS count FROM gaming_sessions WHERE status = 'active'`),
    query<{ count: string }>(`SELECT COUNT(*)::text AS count FROM gpu_servers WHERE status = 'online'`),
    query<{ total: string }>(`SELECT COALESCE(SUM(amount), 0)::text AS total FROM coin_transactions WHERE amount > 0`),
    query<{ count: string }>(`SELECT COUNT(*)::text AS count FROM games WHERE is_available = TRUE`),
    query<{ count: string }>(`SELECT COUNT(*)::text AS count FROM support_tickets WHERE status = 'open'`),
  ]);

  const settingsRes = await query<{ key: string; value: string }>(
    `SELECT key, value FROM platform_settings`
  );
  const settings: Record<string, string> = {};
  for (const row of settingsRes.rows) settings[row.key] = row.value;

  res.json({
    success: true,
    data: {
      total_users: Number(users.rows[0].count),
      active_sessions: Number(activeSessions.rows[0].count),
      online_gpu_servers: Number(onlineServers.rows[0].count),
      total_coins_issued: Number(coinsIssued.rows[0].total),
      available_games: Number(totalGames.rows[0].count),
      open_tickets: Number(openTickets.rows[0].count),
      settings,
    },
  });
});

router.get('/users', async (_req, res) => {
  const rows = await query(
    `SELECT id, username, email, role, coin_balance, is_active, created_at
     FROM users ORDER BY created_at DESC LIMIT 200`
  );
  res.json({ success: true, data: rows.rows });
});

router.patch('/users/:id/status', async (req, res) => {
  const isActive = Boolean(req.body?.is_active);
  await query(`UPDATE users SET is_active = $1, updated_at = NOW() WHERE id = $2`, [
    isActive,
    req.params.id,
  ]);
  await writeAuditLog(req.authUser!.userId, 'ADMIN_USER_STATUS', 'user', req.params.id, { isActive }, req.ip);
  res.json({ success: true, data: { id: req.params.id, is_active: isActive } });
});

router.post('/users/:id/coins', async (req, res) => {
  const schema = z.object({
    amount_delta: z.number().int().refine((n) => n !== 0),
    reason: z.string().min(3).max(200),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Valid non-zero integer amount and reason required.' });
    return;
  }
  try {
    const result = await adjustCoinsByAdmin(
      req.authUser!.userId,
      req.params.id,
      parsed.data.amount_delta,
      parsed.data.reason,
      req.ip
    );
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(400).json({ success: false, error: (err as Error).message });
  }
});

router.get('/games', async (_req, res) => {
  const rows = await query(
    `SELECT g.*, COUNT(sig.server_id)::int AS installed_nodes_count
     FROM games g
     LEFT JOIN server_installed_games sig ON sig.game_id = g.id
     GROUP BY g.id
     ORDER BY g.created_at DESC`
  );
  res.json({ success: true, data: rows.rows });
});

router.post('/games', async (req, res) => {
  const schema = z.object({
    slug: z.string().min(2).max(100),
    title: z.string().min(2).max(160),
    publisher: z.string().min(2).max(120),
    category: z.string().min(2).max(60),
    description: z.string().min(5),
    cover_image_url: z.string().url().optional().or(z.literal('')),
    executable_path: z.string().min(3),
    license_provider: z.string().min(2).default('Steamworks'),
    is_licensed: z.boolean().default(true),
    is_available: z.boolean().default(true),
    max_resolution: z.enum(['720p', '1080p']).default('1080p'),
    max_fps: z.union([z.literal(30), z.literal(60), z.literal(120)]).default(120),
    min_gpu_tier: z.string().default('RTX 3060'),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid game metadata payload.' });
    return;
  }
  const g = parsed.data;
  const created = await query(
    `INSERT INTO games (
       slug, title, publisher, category, description, cover_image_url,
       executable_path, license_provider, is_licensed, is_available,
       max_resolution, max_fps, min_gpu_tier
     ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
     RETURNING *`,
    [
      g.slug, g.title, g.publisher, g.category, g.description, g.cover_image_url || null,
      g.executable_path, g.license_provider, g.is_licensed, g.is_available,
      g.max_resolution, g.max_fps, g.min_gpu_tier,
    ]
  );
  await writeAuditLog(req.authUser!.userId, 'ADMIN_GAME_ADDED', 'game', created.rows[0].id, { title: g.title }, req.ip);
  res.status(201).json({ success: true, data: created.rows[0] });
});

router.patch('/games/:id/availability', async (req, res) => {
  const isAvailable = Boolean(req.body?.is_available);
  await query(`UPDATE games SET is_available = $1, updated_at = NOW() WHERE id = $2`, [
    isAvailable,
    req.params.id,
  ]);
  await writeAuditLog(req.authUser!.userId, 'ADMIN_GAME_TOGGLED', 'game', req.params.id, { isAvailable }, req.ip);
  res.json({ success: true, data: { id: req.params.id, is_available: isAvailable } });
});

router.delete('/games/:id', async (req, res) => {
  await query(`DELETE FROM games WHERE id = $1`, [req.params.id]);
  await writeAuditLog(req.authUser!.userId, 'ADMIN_GAME_DELETED', 'game', req.params.id, {}, req.ip);
  res.json({ success: true, data: { deleted: true } });
});

router.get('/servers', async (_req, res) => {
  const rows = await query(`SELECT * FROM gpu_servers ORDER BY region ASC, name ASC`);
  res.json({ success: true, data: rows.rows });
});

router.patch('/servers/:id/status', async (req, res) => {
  const status = String(req.body?.status || '');
  if (!['online', 'draining', 'offline', 'maintenance'].includes(status)) {
    res.status(400).json({ success: false, error: 'Invalid GPU server status.' });
    return;
  }
  await query(`UPDATE gpu_servers SET status = $1 WHERE id = $2`, [status, req.params.id]);
  res.json({ success: true, data: { id: req.params.id, status } });
});

router.get('/sessions', async (_req, res) => {
  const rows = await query(
    `SELECT gs.*, u.username, u.email, g.title AS game_title, srv.name AS server_name
     FROM gaming_sessions gs
     INNER JOIN users u ON u.id = gs.user_id
     INNER JOIN games g ON g.id = gs.game_id
     INNER JOIN gpu_servers srv ON srv.id = gs.server_id
     ORDER BY gs.started_at DESC LIMIT 100`
  );
  res.json({ success: true, data: rows.rows });
});

router.post('/sessions/:id/terminate', async (req, res) => {
  const sessionId = String(req.params.id);
  const settled = await settleSession(sessionId, 'ended');
  notifySessionTerminated(sessionId);
  await writeAuditLog(req.authUser!.userId, 'ADMIN_FORCE_END_SESSION', 'gaming_session', sessionId, settled, req.ip);
  res.json({ success: true, data: settled });
});

router.get('/transactions', async (_req, res) => {
  const rows = await query(
    `SELECT ct.*, u.username, u.email
     FROM coin_transactions ct
     INNER JOIN users u ON u.id = ct.user_id
     ORDER BY ct.created_at DESC LIMIT 150`
  );
  res.json({ success: true, data: rows.rows });
});

router.get('/tickets', async (_req, res) => {
  const rows = await query(
    `SELECT st.*, u.username, u.email
     FROM support_tickets st
     INNER JOIN users u ON u.id = st.user_id
     ORDER BY st.created_at DESC LIMIT 100`
  );
  res.json({ success: true, data: rows.rows });
});

router.patch('/tickets/:id', async (req, res) => {
  const status = String(req.body?.status || 'resolved');
  const adminReply = String(req.body?.admin_reply || '');
  const updated = await query(
    `UPDATE support_tickets SET status = $1, admin_reply = $2, updated_at = NOW() WHERE id = $3 RETURNING *`,
    [status, adminReply, req.params.id]
  );
  res.json({ success: true, data: updated.rows[0] });
});

router.put('/settings', async (req, res) => {
  const schema = z.object({
    daily_reward_coins: z.number().int().min(1).max(500).optional(),
    coins_per_hour: z.number().int().min(1).max(200).optional(),
    maintenance_mode: z.boolean().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid settings values.' });
    return;
  }
  const updates = parsed.data;
  if (updates.daily_reward_coins !== undefined) {
    await query(
      `INSERT INTO platform_settings (key, value) VALUES ('daily_reward_coins', $1)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()`,
      [String(updates.daily_reward_coins)]
    );
  }
  if (updates.coins_per_hour !== undefined) {
    await query(
      `INSERT INTO platform_settings (key, value) VALUES ('coins_per_hour', $1)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()`,
      [String(updates.coins_per_hour)]
    );
  }
  if (updates.maintenance_mode !== undefined) {
    const val = String(updates.maintenance_mode);
    await query(
      `INSERT INTO platform_settings (key, value) VALUES ('maintenance_mode', $1)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()`,
      [val]
    );
    await redis.set(RedisKeys.maintenanceMode, val, 'EX', 60).catch(() => {});
  }
  await writeAuditLog(req.authUser!.userId, 'ADMIN_SETTINGS_UPDATED', 'platform_settings', 'global', updates, req.ip);
  res.json({ success: true, data: updates });
});

export default router;
