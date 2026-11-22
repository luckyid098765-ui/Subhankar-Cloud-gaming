import { Router } from 'express';
import { z } from 'zod';
import { query, withTransaction } from '../config/database.js';
import { redis, RedisKeys } from '../config/redis.js';
import { requireAuth, requireHostAgent } from '../middleware/auth.js';

const router = Router();

// Public/Authenticated client endpoint to view live GPU cluster status
router.get('/status', requireAuth, async (_req, res) => {
  const rows = await query(
    `SELECT id, name, region, gpu_model, status, gpu_utilization_pct,
            cpu_utilization_pct, ram_used_gb, ram_total_gb, bandwidth_mbps,
            active_sessions, max_sessions
     FROM gpu_servers
     ORDER BY region ASC, name ASC`
  );
  res.json({ success: true, data: rows.rows });
});

// GPU Windows Host Agent heartbeat & telemetry ingestion
router.post('/agent/heartbeat', requireHostAgent, async (req, res) => {
  const schema = z.object({
    name: z.string().min(2).max(120),
    region: z.string().min(2).max(64),
    host_ip: z.string().min(3).max(128),
    signaling_endpoint: z.string().min(3),
    gpu_model: z.string().min(2).max(120),
    ram_total_gb: z.number().positive(),
    max_sessions: z.number().int().min(1).max(32),
    active_sessions: z.number().int().min(0),
    gpu_utilization_pct: z.number().int().min(0).max(100),
    cpu_utilization_pct: z.number().int().min(0).max(100),
    ram_used_gb: z.number().min(0),
    bandwidth_mbps: z.number().int().min(0),
    installed_game_slugs: z.array(z.string()).default([]),
  });

  const parsed = schema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid host agent telemetry payload.' });
    return;
  }

  const d = parsed.data;
  const serverId = await withTransaction(async (client) => {
    const upserted = await client.query<{ id: string }>(
      `INSERT INTO gpu_servers (
         name, region, host_ip, signaling_endpoint, gpu_model, ram_total_gb,
         max_sessions, active_sessions, gpu_utilization_pct, cpu_utilization_pct,
         ram_used_gb, bandwidth_mbps, status, last_heartbeat_at
       ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,'online',NOW())
       ON CONFLICT (name) DO UPDATE SET
         region = EXCLUDED.region,
         host_ip = EXCLUDED.host_ip,
         signaling_endpoint = EXCLUDED.signaling_endpoint,
         gpu_model = EXCLUDED.gpu_model,
         ram_total_gb = EXCLUDED.ram_total_gb,
         max_sessions = EXCLUDED.max_sessions,
         active_sessions = EXCLUDED.active_sessions,
         gpu_utilization_pct = EXCLUDED.gpu_utilization_pct,
         cpu_utilization_pct = EXCLUDED.cpu_utilization_pct,
         ram_used_gb = EXCLUDED.ram_used_gb,
         bandwidth_mbps = EXCLUDED.bandwidth_mbps,
         status = CASE WHEN gpu_servers.status = 'maintenance' THEN 'maintenance' ELSE 'online' END,
         last_heartbeat_at = NOW()
       RETURNING id`,
      [
        d.name, d.region, d.host_ip, d.signaling_endpoint, d.gpu_model, d.ram_total_gb,
        d.max_sessions, d.active_sessions, d.gpu_utilization_pct, d.cpu_utilization_pct,
        d.ram_used_gb, d.bandwidth_mbps,
      ]
    );
    const id = upserted.rows[0].id;

    if (d.installed_game_slugs.length > 0) {
      const games = await client.query<{ id: string }>(
        `SELECT id FROM games WHERE slug = ANY($1::text[])`,
        [d.installed_game_slugs]
      );
      for (const g of games.rows) {
        await client.query(
          `INSERT INTO server_installed_games (server_id, game_id, install_verified)
           VALUES ($1, $2, TRUE)
           ON CONFLICT (server_id, game_id) DO UPDATE SET install_verified = TRUE`,
          [id, g.id]
        );
      }
    }
    return id;
  });

  await redis
    .set(RedisKeys.nodeTelemetry(serverId), JSON.stringify(d), 'EX', 90)
    .catch(() => {});

  res.json({ success: true, data: { server_id: serverId } });
});

export default router;
