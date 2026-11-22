import { execFile, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import { promisify } from 'node:util';
import WebSocket from 'ws';

const execFileAsync = promisify(execFile);

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:4000/api/v1';
const WS_URL = process.env.BACKEND_WS_URL || 'ws://localhost:4000/ws/signaling';
const HOST_AGENT_SECRET = process.env.HOST_AGENT_SECRET || '';
const NODE_NAME = process.env.GPU_NODE_NAME || os.hostname();
const NODE_REGION = process.env.GPU_NODE_REGION || 'asia-south1';
const HOST_PUBLIC_IP = process.env.HOST_PUBLIC_IP || '127.0.0.1';
const MAX_SESSIONS = Number(process.env.MAX_SESSIONS || 2);

const activeGames = new Map<string, { pid: number; ws: WebSocket }>();

async function readGpuMetrics(): Promise<{ gpuModel: string; gpuUtilPct: number }> {
  try {
    const { stdout } = await execFileAsync('nvidia-smi', [
      '--query-gpu=name,utilization.gpu',
      '--format=csv,noheader,nounits',
    ]);
    const [name, util] = stdout.trim().split(',').map((s) => s.trim());
    return { gpuModel: name || 'NVIDIA RTX Cloud GPU', gpuUtilPct: Number(util) || 0 };
  } catch {
    return { gpuModel: 'NVIDIA RTX GPU (nvidia-smi unavailable)', gpuUtilPct: 0 };
  }
}

function detectInstalledSlugs(): string[] {
  const catalogPaths: Record<string, string> = {
    'gta-v': 'C:\\Games\\GTAV\\PlayGTAV.exe',
    'spider-man-remastered': 'C:\\Games\\SpiderManRemastered\\Spider-Man.exe',
    'spider-man-2': 'C:\\Games\\SpiderMan2\\Spider-Man2.exe',
    'red-dead-redemption-2': 'C:\\Games\\RDR2\\RDR2.exe',
    'god-of-war': 'C:\\Games\\GodOfWar\\GoW.exe',
    'forza-horizon-5': 'C:\\Games\\ForzaHorizon5\\ForzaHorizon5.exe',
  };
  return Object.entries(catalogPaths)
    .filter(([, exe]) => fs.existsSync(exe))
    .map(([slug]) => slug);
}

async function sendHeartbeat() {
  const gpu = await readGpuMetrics();
  const totalRamGb = Math.round(os.totalmem() / 1024 ** 3);
  const usedRamGb = Number(((os.totalmem() - os.freemem()) / 1024 ** 3).toFixed(1));
  const cpuLoadPct = Math.min(100, Math.round((os.loadavg()[0] / os.cpus().length) * 100));

  await fetch(`${BACKEND_URL}/servers/agent/heartbeat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-host-agent-secret': HOST_AGENT_SECRET,
    },
    body: JSON.stringify({
      name: NODE_NAME,
      region: NODE_REGION,
      host_ip: HOST_PUBLIC_IP,
      signaling_endpoint: WS_URL,
      gpu_model: gpu.gpuModel,
      ram_total_gb: totalRamGb,
      max_sessions: MAX_SESSIONS,
      active_sessions: activeGames.size,
      gpu_utilization_pct: gpu.gpuUtilPct,
      cpu_utilization_pct: cpuLoadPct,
      ram_used_gb: usedRamGb,
      bandwidth_mbps: 1000,
      installed_game_slugs: detectInstalledSlugs(),
    }),
  });
}

export function launchHostSession(sessionId: string, executablePath: string) {
  const proc = spawn(executablePath, [], { detached: false, stdio: 'ignore' });
  const ws = new WebSocket(WS_URL, {
    headers: { 'x-host-agent-secret': HOST_AGENT_SECRET },
  });

  ws.on('open', () => {
    ws.send(JSON.stringify({ type: 'join_session', sessionId }));
  });

  ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.type === 'session_terminated') {
      proc.kill('SIGTERM');
      ws.close();
      activeGames.delete(sessionId);
    }
  });

  if (proc.pid) {
    activeGames.set(sessionId, { pid: proc.pid, ws });
  }
}

setInterval(() => {
  sendHeartbeat().catch((err) => console.error('[HostAgent] heartbeat error:', err.message));
}, 15_000);

sendHeartbeat().catch((err) => console.error('[HostAgent] initial heartbeat error:', err.message));
