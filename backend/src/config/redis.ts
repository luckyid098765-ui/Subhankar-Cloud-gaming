import { Redis } from 'ioredis';
import { env } from './env.js';

export const redis = new Redis(env.redisUrl, {
  maxRetriesPerRequest: 3,
  lazyConnect: true,
});

redis.on('error', (err) => {
  console.error('[Redis] connection error:', err.message);
});

export const RedisKeys = {
  maintenanceMode: 'platform:maintenance_mode',
  sessionState: (sessionId: string) => `session:${sessionId}`,
  rateLimit: (bucket: string, ip: string) => `ratelimit:${bucket}:${ip}`,
  nodeTelemetry: (serverId: string) => `gpu_node:${serverId}:telemetry`,
};
