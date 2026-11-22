import { Server as HttpServer } from 'http';
import jwt from 'jsonwebtoken';
import { WebSocket, WebSocketServer } from 'ws';
import { env } from '../config/env.js';

interface StreamPeerRoom {
  clientSocket?: WebSocket;
  hostSocket?: WebSocket;
}

const rooms = new Map<string, StreamPeerRoom>();

export function attachSignalingServer(httpServer: HttpServer): void {
  const wss = new WebSocketServer({ server: httpServer, path: '/ws/signaling' });

  wss.on('connection', (ws, req) => {
    let joinedSessionId: string | null = null;
    let peerRole: 'client' | 'host' | null = null;

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString()) as Record<string, unknown>;
        const type = String(msg.type || '');

        if (type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', clientTs: msg.clientTs || Date.now() }));
          return;
        }

        if (type === 'join_session') {
          const sessionId = String(msg.sessionId || '');
          const token = String(msg.token || '');
          const hostSecret = req.headers['x-host-agent-secret'];

          if (typeof hostSecret === 'string' && hostSecret === env.hostAgentSecret) {
            peerRole = 'host';
          } else {
            const decoded = jwt.verify(token, env.jwtAccessSecret) as {
              sessionId: string;
              purpose: string;
            };
            if (decoded.purpose !== 'webrtc_stream' || decoded.sessionId !== sessionId) {
              ws.close(4003, 'Invalid stream token');
              return;
            }
            peerRole = 'client';
          }

          joinedSessionId = sessionId;
          const room = rooms.get(sessionId) || {};
          if (peerRole === 'host') {
            room.hostSocket = ws;
            rooms.set(sessionId, room);
            room.clientSocket?.send(JSON.stringify({ type: 'host_ready', sessionId }));
          } else {
            room.clientSocket = ws;
            rooms.set(sessionId, room);
            if (room.hostSocket && room.hostSocket.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: 'host_ready', sessionId }));
            }
          }
          return;
        }

        if (!joinedSessionId || !peerRole) return;
        const room = rooms.get(joinedSessionId);
        if (!room) return;

        const target = peerRole === 'client' ? room.hostSocket : room.clientSocket;
        if (target && target.readyState === WebSocket.OPEN) {
          target.send(JSON.stringify(msg));
        }
      } catch {
        // Ignore malformed signaling frame
      }
    });

    ws.on('close', () => {
      if (!joinedSessionId || !peerRole) return;
      const room = rooms.get(joinedSessionId);
      if (!room) return;
      if (peerRole === 'client' && room.clientSocket === ws) {
        room.clientSocket = undefined;
      }
      if (peerRole === 'host' && room.hostSocket === ws) {
        room.hostSocket = undefined;
      }
      if (!room.clientSocket && !room.hostSocket) {
        rooms.delete(joinedSessionId);
      }
    });
  });
}

export function notifySessionTerminated(sessionId: string): void {
  const room = rooms.get(sessionId);
  if (!room) return;
  const payload = JSON.stringify({ type: 'session_terminated', sessionId });
  if (room.clientSocket?.readyState === WebSocket.OPEN) room.clientSocket.send(payload);
  if (room.hostSocket?.readyState === WebSocket.OPEN) room.hostSocket.send(payload);
}
