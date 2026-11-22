import bcrypt from 'bcryptjs';
import crypto from 'crypto';
import { Router } from 'express';
import { OAuth2Client } from 'google-auth-library';
import { z } from 'zod';
import { query, withTransaction, writeAuditLog } from '../config/database.js';
import { env } from '../config/env.js';
import {
  rateLimit,
  requireAuth,
  signAccessToken,
  signRefreshToken,
} from '../middleware/auth.js';

const router = Router();
const googleClient = new OAuth2Client(env.googleWebClientId || undefined);

const registerSchema = z.object({
  username: z.string().min(3).max(40),
  email: z.string().email(),
  password: z.string().min(6).max(128),
});

const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(6),
});

const googleSchema = z.object({
  idToken: z.string().min(10),
});

function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

async function issueTokenPair(user: { id: string; email: string; role: 'user' | 'admin'; username: string; coin_balance: number; avatar_url: string | null }) {
  const authPayload = { userId: user.id, role: user.role, email: user.email };
  const accessToken = signAccessToken(authPayload);
  const refreshToken = signRefreshToken(authPayload);
  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);

  await query(
    `INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES ($1, $2, $3)`,
    [user.id, hashToken(refreshToken), expiresAt]
  );

  return {
    user: {
      id: user.id,
      username: user.username,
      email: user.email,
      role: user.role,
      avatar_url: user.avatar_url,
      coin_balance: user.coin_balance,
    },
    accessToken,
    refreshToken,
  };
}

router.post('/register', rateLimit('auth', 15, 60), async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid registration details.' });
    return;
  }
  const { username, email, password } = parsed.data;
  const normalizedEmail = email.toLowerCase();

  try {
    const existing = await query(`SELECT id FROM users WHERE email = $1`, [normalizedEmail]);
    if (existing.rowCount && existing.rowCount > 0) {
      res.status(409).json({ success: false, error: 'An account with this email already exists.' });
      return;
    }
    const passwordHash = await bcrypt.hash(password, 12);
    const created = await query<{
      id: string;
      username: string;
      email: string;
      role: 'user' | 'admin';
      avatar_url: string | null;
      coin_balance: number;
    }>(
      `INSERT INTO users (username, email, password_hash, coin_balance)
       VALUES ($1, $2, $3, 0)
       RETURNING id, username, email, role, avatar_url, coin_balance`,
      [username.trim(), normalizedEmail, passwordHash]
    );
    const user = created.rows[0];
    await writeAuditLog(user.id, 'USER_REGISTERED', 'user', user.id, { email: normalizedEmail }, req.ip);
    const data = await issueTokenPair(user);
    res.status(201).json({ success: true, data });
  } catch (err) {
    console.error(err);
    res.status(500).json({ success: false, error: 'Failed to register user.' });
  }
});

router.post('/login', rateLimit('auth', 20, 60), async (req, res) => {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ success: false, error: 'Invalid email or password format.' });
    return;
  }
  const normalizedEmail = parsed.data.email.toLowerCase();

  const userRes = await query<{
    id: string;
    username: string;
    email: string;
    password_hash: string | null;
    role: 'user' | 'admin';
    avatar_url: string | null;
    coin_balance: number;
    is_active: boolean;
  }>(
    `SELECT id, username, email, password_hash, role, avatar_url, coin_balance, is_active
     FROM users WHERE email = $1`,
    [normalizedEmail]
  );

  const user = userRes.rows[0];
  if (!user || !user.password_hash) {
    res.status(401).json({ success: false, error: 'Invalid email or password.' });
    return;
  }
  if (!user.is_active) {
    res.status(403).json({ success: false, error: 'Account suspended by administrator.' });
    return;
  }
  const match = await bcrypt.compare(parsed.data.password, user.password_hash);
  if (!match) {
    res.status(401).json({ success: false, error: 'Invalid email or password.' });
    return;
  }

  await writeAuditLog(user.id, 'USER_LOGIN', 'user', user.id, {}, req.ip);
  const data = await issueTokenPair(user);
  res.json({ success: true, data });
});

router.post('/google', rateLimit('auth', 20, 60), async (req, res) => {
  const parsed = googleSchema.safeParse(req.body);
  if (!parsed.success || !env.googleWebClientId) {
    res.status(400).json({ success: false, error: 'Google Sign-In is not configured or token is invalid.' });
    return;
  }

  try {
    const ticket = await googleClient.verifyIdToken({
      idToken: parsed.data.idToken,
      audience: env.googleWebClientId,
    });
    const payload = ticket.getPayload();
    if (!payload?.email || !payload.sub) {
      res.status(401).json({ success: false, error: 'Google ID token missing verified email.' });
      return;
    }

    const email = payload.email.toLowerCase();
    const user = await withTransaction(async (client) => {
      const existing = await client.query<{
        id: string;
        username: string;
        email: string;
        role: 'user' | 'admin';
        avatar_url: string | null;
        coin_balance: number;
        is_active: boolean;
      }>(
        `SELECT id, username, email, role, avatar_url, coin_balance, is_active
         FROM users WHERE google_sub = $1 OR email = $2`,
        [payload.sub, email]
      );
      if (existing.rows[0]) {
        await client.query(
          `UPDATE users SET google_sub = COALESCE(google_sub, $1), avatar_url = COALESCE(avatar_url, $2), updated_at = NOW() WHERE id = $3`,
          [payload.sub, payload.picture || null, existing.rows[0].id]
        );
        return existing.rows[0];
      }
      const inserted = await client.query<{
        id: string;
        username: string;
        email: string;
        role: 'user' | 'admin';
        avatar_url: string | null;
        coin_balance: number;
        is_active: boolean;
      }>(
        `INSERT INTO users (username, email, google_sub, avatar_url, coin_balance)
         VALUES ($1, $2, $3, $4, 0)
         RETURNING id, username, email, role, avatar_url, coin_balance, is_active`,
        [payload.name || email.split('@')[0], email, payload.sub, payload.picture || null]
      );
      return inserted.rows[0];
    });

    if (!user.is_active) {
      res.status(403).json({ success: false, error: 'Account suspended by administrator.' });
      return;
    }

    await writeAuditLog(user.id, 'USER_GOOGLE_LOGIN', 'user', user.id, {}, req.ip);
    const data = await issueTokenPair(user);
    res.json({ success: true, data });
  } catch {
    res.status(401).json({ success: false, error: 'Failed to verify Google ID token.' });
  }
});

router.post('/refresh', async (req, res) => {
  const token = String(req.body?.refreshToken || '');
  if (!token) {
    res.status(400).json({ success: false, error: 'Missing refresh token.' });
    return;
  }
  const tokenHash = hashToken(token);
  const stored = await query<{ user_id: string; expires_at: Date; revoked_at: Date | null }>(
    `SELECT user_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = $1`,
    [tokenHash]
  );
  const row = stored.rows[0];
  if (!row || row.revoked_at || new Date(row.expires_at).getTime() < Date.now()) {
    res.status(401).json({ success: false, error: 'Refresh token invalid or expired.' });
    return;
  }

  await query(`UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = $1`, [tokenHash]);
  const userRes = await query<{
    id: string;
    username: string;
    email: string;
    role: 'user' | 'admin';
    avatar_url: string | null;
    coin_balance: number;
    is_active: boolean;
  }>(
    `SELECT id, username, email, role, avatar_url, coin_balance, is_active FROM users WHERE id = $1`,
    [row.user_id]
  );
  const user = userRes.rows[0];
  if (!user || !user.is_active) {
    res.status(403).json({ success: false, error: 'Account unavailable.' });
    return;
  }
  const data = await issueTokenPair(user);
  res.json({ success: true, data });
});

router.post('/logout', async (req, res) => {
  const token = String(req.body?.refreshToken || '');
  if (token) {
    await query(`UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = $1`, [hashToken(token)]);
  }
  res.json({ success: true, data: {} });
});

router.get('/me', requireAuth, async (req, res) => {
  const userRes = await query(
    `SELECT id, username, email, role, avatar_url, coin_balance, created_at
     FROM users WHERE id = $1`,
    [req.authUser!.userId]
  );
  if (!userRes.rows[0]) {
    res.status(404).json({ success: false, error: 'User not found.' });
    return;
  }
  res.json({ success: true, data: userRes.rows[0] });
});

export default router;
