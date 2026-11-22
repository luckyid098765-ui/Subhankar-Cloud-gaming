# Shubhankar Cloud Gaming — REST & WebRTC Signaling API Documentation

Base URL: `https://api.yourdomain.com/api/v1`

All responses follow:
```json
{ "success": true, "data": { ... } }
```

## Authentication
- `POST /auth/register` — `{ username, email, password }` -> Returns `{ user, accessToken, refreshToken }`
- `POST /auth/login` — `{ email, password }` -> Returns `{ user, accessToken, refreshToken }`
- `POST /auth/google` — `{ idToken }` -> Verifies Google OAuth ID token against `GOOGLE_WEB_CLIENT_ID`
- `POST /auth/refresh` — `{ refreshToken }` -> Rotates refresh token and issues a new access token
- `POST /auth/logout` — `{ refreshToken }` -> Revokes refresh token
- `GET /auth/me` (Bearer JWT) -> Returns user profile and coin balance

## Licensed Games Catalog
- `GET /games?search=&category=` (Bearer JWT) -> Lists licensed, available PC titles with online GPU node counts
- `GET /games/:id` (Bearer JWT) -> Detailed metadata, screenshots, resolution/FPS caps, and controller support

## Coin Wallet & Daily Rewards
- `GET /wallet` (Bearer JWT) -> Returns `{ balance, hourly_rate_coins: 10, daily_reward_coins: 20, daily_reward_claimed_today, next_claim_utc, transactions }`
- `POST /wallet/daily-reward` (Bearer JWT) -> Atomically credits **20 coins** once per UTC day (`409` on duplicate claim)

## Cloud Gaming Sessions
- `POST /sessions/start` (Bearer JWT) — `{ game_id, duration_hours, resolution: "720p"|"1080p", target_fps: 30|60|120, codec: "H264"|"H265" }`
  - Verifies game license & availability, prevents negative balance (`402`), reserves `duration_hours * 10` coins, allocates best online GPU node, returns `{ session_id, signaling_ws_url, stream_token, ice_servers, coins_reserved, new_balance, expires_at }`.
- `POST /sessions/:id/end` (Bearer JWT) -> Ends stream, releases GPU slot, settles used minutes, refunds unused coin blocks.
- `GET /sessions/history` (Bearer JWT) -> Returns past and active session records.

## WebRTC Signaling WebSocket (`/ws/signaling`)
- `join_session`: `{ type: "join_session", sessionId, token }`
- `sdp_offer` / `sdp_answer` / `ice_candidate`: Relayed between Android client and GPU host
- `quality_control`: `{ type: "quality_control", sessionId, resolution, fps, maxBitrateKbps, codec }`
- `ping` / `pong`: RTT latency measurement
- `input_relay`: Fallback input relay when WebRTC DataChannel is initializing

## Admin Control Plane (`/admin/*`, requires `role === "admin"`)
- `GET /admin/analytics`
- `GET /admin/users`, `PATCH /admin/users/:id/status`, `POST /admin/users/:id/coins`
- `GET /admin/games`, `POST /admin/games`, `PATCH /admin/games/:id/availability`, `DELETE /admin/games/:id`
- `GET /admin/servers`, `PATCH /admin/servers/:id/status`
- `GET /admin/sessions`, `POST /admin/sessions/:id/terminate`
- `GET /admin/transactions`, `GET /admin/tickets`, `PATCH /admin/tickets/:id`, `PUT /admin/settings`
