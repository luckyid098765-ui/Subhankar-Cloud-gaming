import { NextFunction, Request, Response } from 'express';
import jwt from 'jsonwebtoken';
import { env } from '../config/env.js';
import { query } from '../config/database.js';
import { redis, RedisKeys } from '../config/redis.js';

export interface AuthUser {
  userId: string;
  role: 'user' | 'admin';
  email: string;
}

declare global {
  namespace Express {
    interface Request {
      authUser?: AuthUser;
    }
  }
}

export function signAccessToken(payload: AuthUser): string {
  return jwt.sign(payload, env.jwtAccessSecret, { expiresIn: '15m' });
}

export function signRefreshToken(payload: AuthUser): string {
  return jwt.sign(payload, env.jwtRefreshSecret, { expiresIn: '30d' });
}

export function signStreamToken(sessionId: string, userId: string, serverId: string): string {
  return jwt.sign(
    { sessionId, userId, serverId, purpose: 'webrtc_stream' },
    env.jwtAccessSecret,
    { expiresIn: '3h' }
  );
}

export async function requireAuth(req: Request, res: Response, next: NextFunction): Promise<void> {
  const header = req.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    res.status(401).json({ success: false, error: 'Missing authorization token' });
    return;
  }
  const token = header.slice(7);
  try {
    const decoded = jwt.verify(token, env.jwtAccessSecret) as AuthUser;
    req.authUser = decoded;
    next();
  } catch {
    res.status(401).json({ success: false, error: 'Invalid or expired access token' });
  }
}

export function requireAdmin(req: Request, res: Response, next: NextFunction): void {
  if (!req.authUser || req.authUser.role !== 'admin') {
    res.status(403).json({ success: false, error: 'Administrator privileges required' });
    return;
  }
  next();
}

export function requireHostAgent(req: Request, res: Response, next: NextFunction): void {
  const secret = req.headers['x-host-agent-secret'];
  if (typeof secret !== 'string' || secret !== env.hostAgentSecret) {
    res.status(403).json({ success: false, error: 'Invalid GPU host agent credentials' });
    return;
  }
  next();
}

export async function checkMaintenanceMode(req: Request, res: Response, next: NextFunction): Promise<void> {
  if (req.authUser?.role === 'admin') {
    next();
    return;
  }
  let maintenance = await redis.get(RedisKeys.maintenanceMode).catch(() => null);
  if (maintenance === null) {
    const dbRes = await query<{ value: string }>(
      `SELECT value FROM platform_settings WHERE key = 'maintenance_mode'`
    );
    maintenance = dbRes.rows[0]?.value || 'false';
    await redis.set(RedisKeys.maintenanceMode, maintenance, 'EX', 30).catch(() => {});
  }
  if (maintenance === 'true') {
    res.status(503).json({
      success: false,
      error: 'Platform is currently in scheduled maintenance mode.',
    });
    return;
  }
  next();
}

export function rateLimit(bucket: string, maxRequests: number, windowSeconds: number) {
  return async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    const ip = req.ip || req.socket.remoteAddress || 'unknown';
    const key = RedisKeys.rateLimit(bucket, ip);
    try {
      const current = await redis.incr(key);
      if (current === 1) {
        await redis.expire(key, windowSeconds);
      }
      if (current > maxRequests) {
        res.status(429).json({ success: false, error: 'Too many requests. Please slow down.' });
        return;
      }
    } catch {
      // Fail open if Redis is temporarily unavailable
    }
    next();
  };
}
