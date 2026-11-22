# Shubhankar Cloud Gaming — Deployment Guide

## 1. Prerequisites
- PostgreSQL 16+
- Redis 7+
- Node.js 22+
- Coturn TURN/STUN server (recommended for mobile data NAT traversal)
- Windows GPU Server(s) with NVIDIA RTX / GRID GPUs (`nvidia-smi` + NVENC) and legally licensed PC games installed

## 2. Database & Backend Setup
1. Configure `/backend/.env` from `/backend/.env.example` with strong 64-character JWT secrets, PostgreSQL DSN, Redis URL, `GOOGLE_WEB_CLIENT_ID`, `HOST_AGENT_SECRET`, and TURN credentials.
2. Install dependencies and run migrations:
   ```bash
   cd backend
   npm install
   npm run migrate
   ADMIN_EMAIL=admin@yourdomain.com ADMIN_PASSWORD=StrongPassword123! npm run seed:admin
   npm run build
   npm start
   ```
   Or launch the full stack with Docker Compose:
   ```bash
   cd cloud-infra
   docker compose up -d --build
   ```

## 3. Admin Dashboard Setup
```bash
cd admin-dashboard
npm install
VITE_API_URL=https://api.yourdomain.com/api/v1 npm run build
```
Serve `admin-dashboard/dist` behind HTTPS (Nginx / Caddy / Cloudflare Pages).

## 4. Windows GPU Cloud Node Setup
1. Run `/cloud-infra/windows-host/setup-gpu-node.ps1` as Administrator on each Windows GPU machine.
2. Ensure licensed games are installed at the matching paths configured in the Admin Dashboard.
3. Run the GPU Host Agent:
   ```bash
   BACKEND_URL=https://api.yourdomain.com/api/v1 BACKEND_WS_URL=wss://api.yourdomain.com/ws/signaling HOST_AGENT_SECRET=your_secret npx tsx cloud-infra/windows-host/host-agent.ts
   ```

## 5. Android APK Build
- Update `DEFAULT_API_URL` and `GOOGLE_WEB_CLIENT_ID` in `app/build.gradle.kts` (or configure Server URL directly in the Android app Settings screen).
- Build release APK / AAB with `gradle :app:assembleRelease` or `gradle :app:bundleRelease`.
