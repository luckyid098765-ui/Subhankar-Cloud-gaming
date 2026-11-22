import { Router } from 'express';
import { query } from '../config/database.js';
import { checkMaintenanceMode, requireAuth } from '../middleware/auth.js';

const router = Router();

router.get('/', requireAuth, checkMaintenanceMode, async (req, res) => {
  const search = typeof req.query.search === 'string' ? req.query.search.trim() : '';
  const category = typeof req.query.category === 'string' ? req.query.category.trim() : '';

  const conditions: string[] = ['g.is_licensed = TRUE', 'g.is_available = TRUE'];
  const params: unknown[] = [];

  if (search) {
    params.push(`%${search}%`);
    conditions.push(`(g.title ILIKE $${params.length} OR g.publisher ILIKE $${params.length})`);
  }
  if (category && category !== 'All') {
    params.push(category);
    conditions.push(`g.category = $${params.length}`);
  }

  const sql = `
    SELECT
      g.id, g.slug, g.title, g.publisher, g.category, g.description,
      g.cover_image_url, g.screenshots, g.is_licensed, g.is_available,
      g.max_resolution, g.max_fps, g.min_gpu_tier, g.supports_gamepad, g.supports_kbm,
      COUNT(sig.server_id) FILTER (WHERE s.status = 'online' AND sig.install_verified = TRUE)::int AS installed_nodes_count
    FROM games g
    LEFT JOIN server_installed_games sig ON sig.game_id = g.id
    LEFT JOIN gpu_servers s ON s.id = sig.server_id
    WHERE ${conditions.join(' AND ')}
    GROUP BY g.id
    ORDER BY g.title ASC
  `;

  const result = await query(sql, params);
  res.json({ success: true, data: result.rows });
});

router.get('/:id', requireAuth, checkMaintenanceMode, async (req, res) => {
  const result = await query(
    `SELECT
      g.id, g.slug, g.title, g.publisher, g.category, g.description,
      g.cover_image_url, g.screenshots, g.is_licensed, g.is_available,
      g.max_resolution, g.max_fps, g.min_gpu_tier, g.supports_gamepad, g.supports_kbm,
      COUNT(sig.server_id) FILTER (WHERE s.status = 'online' AND sig.install_verified = TRUE)::int AS installed_nodes_count
     FROM games g
     LEFT JOIN server_installed_games sig ON sig.game_id = g.id
     LEFT JOIN gpu_servers s ON s.id = sig.server_id
     WHERE g.id = $1 AND g.is_licensed = TRUE
     GROUP BY g.id`,
    [req.params.id]
  );

  if (!result.rows[0]) {
    res.status(404).json({ success: false, error: 'Licensed game not found.' });
    return;
  }
  res.json({ success: true, data: result.rows[0] });
});

export default router;
