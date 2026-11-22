-- SHUBHANKAR CLOUD GAMING - Initial PostgreSQL Schema (Migration 001)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(64) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash TEXT,
    google_sub VARCHAR(255) UNIQUE,
    avatar_url TEXT,
    role VARCHAR(16) NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    coin_balance INTEGER NOT NULL DEFAULT 0 CHECK (coin_balance >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS games (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(120) NOT NULL UNIQUE,
    title VARCHAR(180) NOT NULL,
    publisher VARCHAR(140) NOT NULL,
    category VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    cover_image_url TEXT,
    screenshots JSONB NOT NULL DEFAULT '[]'::jsonb,
    executable_path TEXT NOT NULL,
    launch_args TEXT NOT NULL DEFAULT '',
    license_provider VARCHAR(64) NOT NULL DEFAULT 'Steam/Publisher',
    is_licensed BOOLEAN NOT NULL DEFAULT TRUE,
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    max_resolution VARCHAR(16) NOT NULL DEFAULT '1080p',
    max_fps INTEGER NOT NULL DEFAULT 120 CHECK (max_fps IN (30, 60, 120)),
    min_gpu_tier VARCHAR(64) NOT NULL DEFAULT 'RTX 3060',
    supports_gamepad BOOLEAN NOT NULL DEFAULT TRUE,
    supports_kbm BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS gpu_servers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL UNIQUE,
    region VARCHAR(64) NOT NULL,
    host_ip VARCHAR(128) NOT NULL,
    signaling_endpoint TEXT NOT NULL,
    gpu_model VARCHAR(120) NOT NULL,
    ram_total_gb REAL NOT NULL DEFAULT 32.0,
    max_sessions INTEGER NOT NULL DEFAULT 4 CHECK (max_sessions >= 1),
    active_sessions INTEGER NOT NULL DEFAULT 0 CHECK (active_sessions >= 0),
    gpu_utilization_pct INTEGER NOT NULL DEFAULT 0,
    cpu_utilization_pct INTEGER NOT NULL DEFAULT 0,
    ram_used_gb REAL NOT NULL DEFAULT 0,
    bandwidth_mbps INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'offline' CHECK (status IN ('online', 'draining', 'offline', 'maintenance')),
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS server_installed_games (
    server_id UUID NOT NULL REFERENCES gpu_servers(id) ON DELETE CASCADE,
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    install_verified BOOLEAN NOT NULL DEFAULT TRUE,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (server_id, game_id)
);

CREATE TABLE IF NOT EXISTS gaming_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE RESTRICT,
    server_id UUID NOT NULL REFERENCES gpu_servers(id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL DEFAULT 'active' CHECK (status IN ('provisioning', 'active', 'ended', 'failed', 'expired')),
    resolution VARCHAR(16) NOT NULL DEFAULT '720p',
    target_fps INTEGER NOT NULL DEFAULT 60,
    codec VARCHAR(16) NOT NULL DEFAULT 'H265',
    duration_minutes INTEGER NOT NULL,
    coins_reserved INTEGER NOT NULL CHECK (coins_reserved >= 0),
    coins_charged INTEGER NOT NULL DEFAULT 0 CHECK (coins_charged >= 0),
    avg_latency_ms INTEGER,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS coin_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID REFERENCES gaming_sessions(id) ON DELETE SET NULL,
    amount INTEGER NOT NULL,
    type VARCHAR(32) NOT NULL CHECK (type IN ('daily_reward', 'session_reserve', 'session_refund', 'admin_grant', 'admin_deduct')),
    description TEXT NOT NULL,
    balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS daily_reward_claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    claim_date DATE NOT NULL,
    coins_awarded INTEGER NOT NULL CHECK (coins_awarded > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, claim_date)
);

CREATE TABLE IF NOT EXISTS support_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(64) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    device_info VARCHAR(200),
    status VARCHAR(24) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'resolved', 'closed')),
    admin_reply TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS platform_settings (
    key VARCHAR(64) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_status ON gaming_sessions (user_id, status);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON gaming_sessions (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_tx_user_created ON coin_transactions (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_games_category ON games (category, is_available, is_licensed);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs (created_at DESC);
