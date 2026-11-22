import dotenv from 'dotenv';

dotenv.config();

function requireEnv(name: string, fallback?: string): string {
  const val = process.env[name] || fallback;
  if (!val) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return val;
}

export const env = {
  port: Number(process.env.PORT || 4000),
  nodeEnv: process.env.NODE_ENV || 'development',
  databaseUrl: requireEnv('DATABASE_URL', 'postgresql://shubhankar:shubhankar@localhost:5432/shubhankar_cloud'),
  redisUrl: requireEnv('REDIS_URL', 'redis://localhost:6379'),
  jwtAccessSecret: requireEnv('JWT_ACCESS_SECRET', 'dev_access_secret_change_in_production_64chars'),
  jwtRefreshSecret: requireEnv('JWT_REFRESH_SECRET', 'dev_refresh_secret_change_in_production_64chars'),
  hostAgentSecret: requireEnv('HOST_AGENT_SECRET', 'dev_host_agent_secret_change_in_production'),
  googleWebClientId: process.env.GOOGLE_WEB_CLIENT_ID || '',
  publicWsBaseUrl: process.env.PUBLIC_WS_BASE_URL || 'ws://localhost:4000',
  stunUrl: process.env.STUN_URL || 'stun:stun.l.google.com:19302',
  turnUrl: process.env.TURN_URL || '',
  turnUsername: process.env.TURN_USERNAME || '',
  turnCredential: process.env.TURN_CREDENTIAL || '',
  corsOrigins: (process.env.CORS_ORIGINS || 'http://localhost:5173').split(',').map((s) => s.trim()),
};
