import { Router } from 'express';
import { z } from 'zod';
import { query } from '../config/database.js';
import { requireAuth } from '../middleware/auth.js';

const router = Router();

const ticketSchema = z.object({
  category: z.string().min(2).max(64),
  subject: z.string().min(3).max(180),
  message: z.string().min(5).max(2000),
  device_info: z.string().max(200).optional(),
});

router.get('/tickets', requireAuth, async (req, res) => {
  const rows = await query(
    `SELECT id, category, subject, message, status, admin_reply, created_at
     FROM support_tickets WHERE user_id = $1 ORDER BY created_at DESC LIMIT 30`,
    [req.authUser!.userId]
  );
  res.json({ success: true, data: rows.rows });
});

router.post('/tickets', requireAuth, async (req, res) => {
  const parsed = ticketSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid support ticket payload.' });
    return;
  }
  const inserted = await query(
    `INSERT INTO support_tickets (user_id, category, subject, message, device_info)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING id, category, subject, message, status, admin_reply, created_at`,
    [
      req.authUser!.userId,
      parsed.data.category,
      parsed.data.subject,
      parsed.data.message,
      parsed.data.device_info || null,
    ]
  );
  res.status(201).json({ success: true, data: inserted.rows[0] });
});

export default router;
