import cors from 'cors';
import express from 'express';
import helmet from 'helmet';
import http from 'http';
import { query } from './config/database.js';
import { env } from './config/env.js';
import adminRoutes from './routes/admin.js';
import agentRoutes from './routes/agent.js';
import authRoutes from './routes/auth.js';
import gamesRoutes from './routes/games.js';
import sessionsRoutes from './routes/sessions.js';
import supportRoutes from './routes/support.js';
import walletRoutes from './routes/wallet.js';
import { runSessionCleanupAndHealthMonitor } from './services/serverAllocator.js';
import { attachSignalingServer } from './services/signalingService.js';

const app = express();
app.use(helmet());
app.use(cors({ origin: env.corsOrigins, credentials: true }));
app.use(express.json({ limit: '1mb' }));

app.get('/api/v1/health', async (_req, res) => {
  try {
    const [nodes, maint] = await Promise.all([
      query<{ count: string }>(`SELECT COUNT(*)::text AS count FROM gpu_servers WHERE status = 'online'`),
      query<{ value: string }>(`SELECT value FROM platform_settings WHERE key = 'maintenance_mode'`),
    ]);
    res.json({
      success: true,
      data: {
        status: 'ok',
        maintenance_mode: maint.rows[0]?.value === 'true',
        online_gpu_nodes: Number(nodes.rows[0]?.count || 0),
        timestamp: new Date().toISOString(),
      },
    });
  } catch (err) {
    res.status(500).json({ success: false, error: (err as Error).message });
  }
});

app.use('/api/v1/auth', authRoutes);
app.use('/api/v1/games', gamesRoutes);
app.use('/api/v1/wallet', walletRoutes);
app.use('/api/v1/sessions', sessionsRoutes);
app.use('/api/v1/servers', agentRoutes);
app.use('/api/v1/support', supportRoutes);
app.use('/api/v1/admin', adminRoutes);

const server = http.createServer(app);
attachSignalingServer(server);

// Background reaper: cleans expired coin sessions & marks stale GPU hosts offline
setInterval(() => {
  runSessionCleanupAndHealthMonitor().catch((err) => {
    console.error('[Monitor] cleanup error:', err);
  });
}, 15_000);

server.listen(env.port, () => {
  console.log(`[Shubhankar Cloud Backend] Listening on port ${env.port}`);
});
