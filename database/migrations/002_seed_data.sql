-- SHUBHANKAR CLOUD GAMING - Seed Data (Migration 002)

INSERT INTO platform_settings (key, value) VALUES
    ('daily_reward_coins', '20'),
    ('coins_per_hour', '10'),
    ('maintenance_mode', 'false')
ON CONFLICT (key) DO NOTHING;

INSERT INTO games (
    slug, title, publisher, category, description,
    executable_path, license_provider, is_licensed, is_available,
    max_resolution, max_fps, min_gpu_tier, supports_gamepad, supports_kbm
) VALUES
(
    'gta-v',
    'Grand Theft Auto V',
    'Rockstar Games',
    'Open World',
    'Experience Los Santos and Blaine County with full cloud-hosted PC graphics, low-latency WebRTC input, and seamless controller or keyboard/mouse play.',
    'C:\\Games\\GTAV\\PlayGTAV.exe',
    'Rockstar Games Launcher',
    TRUE, TRUE, '1080p', 120, 'RTX 3060', TRUE, TRUE
),
(
    'spider-man-remastered',
    'Marvel''s Spider-Man Remastered',
    'PlayStation Publishing / Insomniac',
    'Action',
    'Swing through Marvel''s New York with ray-traced reflections and responsive virtual or Bluetooth gamepad controls.',
    'C:\\Games\\SpiderManRemastered\\Spider-Man.exe',
    'Steamworks PC License',
    TRUE, TRUE, '1080p', 120, 'RTX 3070', TRUE, TRUE
),
(
    'spider-man-2',
    'Marvel''s Spider-Man 2',
    'PlayStation Publishing / Insomniac',
    'Action',
    'Switch between Peter Parker and Miles Morales across an expanded New York powered by high-VRAM cloud GPUs.',
    'C:\\Games\\SpiderMan2\\Spider-Man2.exe',
    'Steamworks PC License',
    TRUE, TRUE, '1080p', 120, 'RTX 4070', TRUE, TRUE
),
(
    'red-dead-redemption-2',
    'Red Dead Redemption 2',
    'Rockstar Games',
    'Open World',
    'Explore America''s unforgiving heartland at 1080p 60/120 FPS streamed via H.265 hardware encoding.',
    'C:\\Games\\RDR2\\RDR2.exe',
    'Rockstar Games Launcher',
    TRUE, TRUE, '1080p', 60, 'RTX 3070', TRUE, TRUE
),
(
    'god-of-war',
    'God of War',
    'PlayStation Publishing / Santa Monica Studio',
    'RPG',
    'Journey through the Norse realms as Kratos and Atreus with ultra PC presets and haptic controller support.',
    'C:\\Games\\GodOfWar\\GoW.exe',
    'Steamworks PC License',
    TRUE, TRUE, '1080p', 120, 'RTX 3060', TRUE, TRUE
),
(
    'forza-horizon-5',
    'Forza Horizon 5',
    'Xbox Game Studios / Playground Games',
    'Racing',
    'Race across vibrant Mexico landscapes with analog trigger support and 120 FPS adaptive WebRTC streaming.',
    'C:\\Games\\ForzaHorizon5\\ForzaHorizon5.exe',
    'Microsoft Store / Steam',
    TRUE, TRUE, '1080p', 120, 'RTX 3060', TRUE, TRUE
)
ON CONFLICT (slug) DO NOTHING;
