import React, { useEffect, useState } from 'react';
import {
  Activity,
  Coins,
  Gamepad2,
  LayoutDashboard,
  LogOut,
  MessageSquareWarning,
  Server,
  Settings,
  Users,
} from 'lucide-react';
import { apiRequest } from './api';

type Tab = 'overview' | 'games' | 'servers' | 'users' | 'sessions' | 'transactions' | 'tickets' | 'settings';

export default function App() {
  const [token, setToken] = useState<string | null>(localStorage.getItem('admin_access_token'));
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [authError, setAuthError] = useState('');
  const [tab, setTab] = useState<Tab>('overview');

  const [analytics, setAnalytics] = useState<any>(null);
  const [games, setGames] = useState<any[]>([]);
  const [servers, setServers] = useState<any[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [sessions, setSessions] = useState<any[]>([]);
  const [transactions, setTransactions] = useState<any[]>([]);
  const [tickets, setTickets] = useState<any[]>([]);
  const [statusBanner, setStatusBanner] = useState('');

  const [newGame, setNewGame] = useState({
    slug: '',
    title: '',
    publisher: '',
    category: 'Action',
    description: '',
    executable_path: 'C:\\Games\\',
    max_resolution: '1080p',
    max_fps: 120,
    min_gpu_tier: 'RTX 3060',
  });

  const [coinForm, setCoinForm] = useState({ userId: '', delta: 20, reason: 'Admin grant' });
  const [platformSettings, setPlatformSettings] = useState({
    daily_reward_coins: 20,
    coins_per_hour: 10,
    maintenance_mode: false,
  });

  async function loadAll() {
    try {
      const [a, g, s, u, sess, tx, tk] = await Promise.all([
        apiRequest<any>('/admin/analytics'),
        apiRequest<any[]>('/admin/games'),
        apiRequest<any[]>('/admin/servers'),
        apiRequest<any[]>('/admin/users'),
        apiRequest<any[]>('/admin/sessions'),
        apiRequest<any[]>('/admin/transactions'),
        apiRequest<any[]>('/admin/tickets'),
      ]);
      setAnalytics(a);
      setGames(g);
      setServers(s);
      setUsers(u);
      setSessions(sess);
      setTransactions(tx);
      setTickets(tk);
      if (a?.settings) {
        setPlatformSettings({
          daily_reward_coins: Number(a.settings.daily_reward_coins || 20),
          coins_per_hour: Number(a.settings.coins_per_hour || 10),
          maintenance_mode: a.settings.maintenance_mode === 'true',
        });
      }
    } catch (err) {
      setStatusBanner((err as Error).message);
    }
  }

  useEffect(() => {
    if (token) loadAll();
  }, [token]);

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault();
    setAuthError('');
    try {
      const data = await apiRequest<{ user: { role: string }; accessToken: string }>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      });
      if (data.user.role !== 'admin') {
        throw new Error('Administrator role required.');
      }
      localStorage.setItem('admin_access_token', data.accessToken);
      setToken(data.accessToken);
    } catch (err) {
      setAuthError((err as Error).message);
    }
  }

  if (!token) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100vh', padding: 20 }}>
        <form className="card" style={{ width: '100%', maxWidth: 420 }} onSubmit={handleLogin}>
          <h2 style={{ color: 'var(--neon-cyan)', marginTop: 0 }}>SHUBHANKAR ADMIN PANEL</h2>
          <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>
            Authenticate with an administrator account to manage GPU nodes, games, users, and coins.
          </p>
          <input
            type="email"
            placeholder="Admin Email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
          <input
            type="password"
            placeholder="Password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {authError && <p style={{ color: 'var(--crimson)', fontSize: 13 }}>{authError}</p>}
          <button className="btn" style={{ width: '100%' }} type="submit">
            Sign In to Control Plane
          </button>
        </form>
      </div>
    );
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">SHUBHANKAR ADMIN</div>
        {[
          ['overview', 'Overview & Analytics', LayoutDashboard],
          ['servers', 'GPU Servers', Server],
          ['games', 'Game Catalog', Gamepad2],
          ['users', 'Users & Coins', Users],
          ['sessions', 'Active Sessions', Activity],
          ['transactions', 'Coin Ledger', Coins],
          ['tickets', 'Support Reports', MessageSquareWarning],
          ['settings', 'Platform & Maintenance', Settings],
        ].map(([key, label, IconComp]: any) => (
          <button
            key={key}
            className={`nav-btn ${tab === key ? 'active' : ''}`}
            onClick={() => setTab(key)}
          >
            <IconComp size={16} />
            {label}
          </button>
        ))}
        <button
          className="nav-btn"
          style={{ marginTop: 'auto', color: 'var(--crimson)' }}
          onClick={() => {
            localStorage.removeItem('admin_access_token');
            setToken(null);
          }}
        >
          <LogOut size={16} /> Sign Out
        </button>
      </aside>

      <main className="main">
        {statusBanner && (
          <div className="card" style={{ marginBottom: 16, borderColor: 'var(--neon-cyan)' }}>
            {statusBanner}
          </div>
        )}

        {tab === 'overview' && analytics && (
          <>
            <h2>Platform Overview</h2>
            <div className="grid-kpi">
              <div className="card"><div>Total Users</div><div className="kpi-val">{analytics.total_users}</div></div>
              <div className="card"><div>Active Sessions</div><div className="kpi-val">{analytics.active_sessions}</div></div>
              <div className="card"><div>Online GPU Nodes</div><div className="kpi-val">{analytics.online_gpu_servers}</div></div>
              <div className="card"><div>Available Games</div><div className="kpi-val">{analytics.available_games}</div></div>
              <div className="card"><div>Coins Issued</div><div className="kpi-val">{analytics.total_coins_issued}</div></div>
              <div className="card"><div>Open Tickets</div><div className="kpi-val">{analytics.open_tickets}</div></div>
            </div>
          </>
        )}

        {tab === 'servers' && (
          <div className="card">
            <h3>GPU Cloud Nodes</h3>
            <table>
              <thead>
                <tr><th>Node</th><th>Region</th><th>GPU</th><th>Utilization</th><th>RAM</th><th>Slots</th><th>Status</th><th>Action</th></tr>
              </thead>
              <tbody>
                {servers.map((s) => (
                  <tr key={s.id}>
                    <td>{s.name}</td>
                    <td>{s.region}</td>
                    <td>{s.gpu_model}</td>
                    <td>GPU {s.gpu_utilization_pct}% / CPU {s.cpu_utilization_pct}%</td>
                    <td>{s.ram_used_gb}/{s.ram_total_gb} GB</td>
                    <td>{s.active_sessions}/{s.max_sessions}</td>
                    <td><span className={`badge ${s.status === 'online' ? 'badge-online' : 'badge-offline'}`}>{s.status}</span></td>
                    <td>
                      <button
                        className="btn-purple btn"
                        onClick={async () => {
                          const next = s.status === 'maintenance' ? 'online' : 'maintenance';
                          await apiRequest(`/admin/servers/${s.id}/status`, {
                            method: 'PATCH',
                            body: JSON.stringify({ status: next }),
                          });
                          loadAll();
                        }}
                      >
                        Toggle Maint
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'games' && (
          <div style={{ display: 'grid', gap: 20 }}>
            <form
              className="card"
              onSubmit={async (e) => {
                e.preventDefault();
                await apiRequest('/admin/games', { method: 'POST', body: JSON.stringify(newGame) });
                setStatusBanner(`Added ${newGame.title} to licensed catalog.`);
                loadAll();
              }}
            >
              <h3>Add Licensed PC Game (No APK Update Needed)</h3>
              <input placeholder="Slug (e.g. cyberpunk-2077)" value={newGame.slug} onChange={(e) => setNewGame({ ...newGame, slug: e.target.value })} required />
              <input placeholder="Title" value={newGame.title} onChange={(e) => setNewGame({ ...newGame, title: e.target.value })} required />
              <input placeholder="Publisher" value={newGame.publisher} onChange={(e) => setNewGame({ ...newGame, publisher: e.target.value })} required />
              <input placeholder="Category (Action, Open World, Racing, RPG)" value={newGame.category} onChange={(e) => setNewGame({ ...newGame, category: e.target.value })} required />
              <input placeholder="Windows Executable Path" value={newGame.executable_path} onChange={(e) => setNewGame({ ...newGame, executable_path: e.target.value })} required />
              <textarea placeholder="Description" value={newGame.description} onChange={(e) => setNewGame({ ...newGame, description: e.target.value })} required />
              <button className="btn" type="submit">Publish Game to Catalog</button>
            </form>

            <div className="card">
              <h3>Catalog Titles</h3>
              <table>
                <thead><tr><th>Title</th><th>Category</th><th>Spec</th><th>Nodes</th><th>Status</th><th>Controls</th></tr></thead>
                <tbody>
                  {games.map((g) => (
                    <tr key={g.id}>
                      <td>{g.title}</td>
                      <td>{g.category}</td>
                      <td>{g.max_resolution} @ {g.max_fps}FPS ({g.min_gpu_tier})</td>
                      <td>{g.installed_nodes_count}</td>
                      <td>{g.is_available ? 'Enabled' : 'Disabled'}</td>
                      <td style={{ display: 'flex', gap: 8 }}>
                        <button
                          className="btn"
                          onClick={async () => {
                            await apiRequest(`/admin/games/${g.id}/availability`, {
                              method: 'PATCH',
                              body: JSON.stringify({ is_available: !g.is_available }),
                            });
                            loadAll();
                          }}
                        >
                          {g.is_available ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          className="btn btn-danger"
                          onClick={async () => {
                            await apiRequest(`/admin/games/${g.id}`, { method: 'DELETE' });
                            loadAll();
                          }}
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {tab === 'users' && (
          <div style={{ display: 'grid', gap: 20 }}>
            <form
              className="card"
              onSubmit={async (e) => {
                e.preventDefault();
                await apiRequest(`/admin/users/${coinForm.userId}/coins`, {
                  method: 'POST',
                  body: JSON.stringify({ amount_delta: Number(coinForm.delta), reason: coinForm.reason }),
                });
                setStatusBanner('Coin adjustment recorded in audit log.');
                loadAll();
              }}
            >
              <h3>Adjust User Coin Balance</h3>
              <select value={coinForm.userId} onChange={(e) => setCoinForm({ ...coinForm, userId: e.target.value })} required>
                <option value="">Select user...</option>
                {users.map((u) => <option key={u.id} value={u.id}>{u.username} ({u.email}) — {u.coin_balance} coins</option>)}
              </select>
              <input type="number" placeholder="Coin delta (+ or -)" value={coinForm.delta} onChange={(e) => setCoinForm({ ...coinForm, delta: Number(e.target.value) })} required />
              <input placeholder="Audit reason" value={coinForm.reason} onChange={(e) => setCoinForm({ ...coinForm, reason: e.target.value })} required />
              <button className="btn" type="submit">Apply Coin Adjustment</button>
            </form>

            <div className="card">
              <h3>Registered Users</h3>
              <table>
                <thead><tr><th>Username</th><th>Email</th><th>Role</th><th>Coins</th><th>Status</th><th>Action</th></tr></thead>
                <tbody>
                  {users.map((u) => (
                    <tr key={u.id}>
                      <td>{u.username}</td><td>{u.email}</td><td>{u.role}</td><td>{u.coin_balance}</td>
                      <td>{u.is_active ? 'Active' : 'Suspended'}</td>
                      <td>
                        <button
                          className="btn btn-danger"
                          onClick={async () => {
                            await apiRequest(`/admin/users/${u.id}/status`, {
                              method: 'PATCH',
                              body: JSON.stringify({ is_active: !u.is_active }),
                            });
                            loadAll();
                          }}
                        >
                          {u.is_active ? 'Suspend' : 'Activate'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {tab === 'sessions' && (
          <div className="card">
            <h3>Gaming Sessions Monitor</h3>
            <table>
              <thead><tr><th>User</th><th>Game</th><th>Node</th><th>Stream</th><th>Status</th><th>Coins</th><th>Action</th></tr></thead>
              <tbody>
                {sessions.map((s) => (
                  <tr key={s.id}>
                    <td>{s.username}</td><td>{s.game_title}</td><td>{s.server_name}</td>
                    <td>{s.resolution} {s.target_fps}FPS ({s.codec})</td>
                    <td>{s.status}</td><td>{s.coins_charged}</td>
                    <td>
                      {s.status === 'active' && (
                        <button
                          className="btn btn-danger"
                          onClick={async () => {
                            await apiRequest(`/admin/sessions/${s.id}/terminate`, { method: 'POST' });
                            loadAll();
                          }}
                        >
                          Force End
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'transactions' && (
          <div className="card">
            <h3>Global Coin Transaction History</h3>
            <table>
              <thead><tr><th>User</th><th>Type</th><th>Amount</th><th>Balance After</th><th>Description</th><th>Time</th></tr></thead>
              <tbody>
                {transactions.map((t) => (
                  <tr key={t.id}>
                    <td>{t.username}</td><td>{t.type}</td><td>{t.amount}</td><td>{t.balance_after}</td><td>{t.description}</td><td>{t.created_at}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'tickets' && (
          <div className="card">
            <h3>User Reports & Support Tickets</h3>
            <table>
              <thead><tr><th>User</th><th>Category</th><th>Subject</th><th>Message</th><th>Status</th><th>Resolve</th></tr></thead>
              <tbody>
                {tickets.map((t) => (
                  <tr key={t.id}>
                    <td>{t.username}</td><td>{t.category}</td><td>{t.subject}</td><td>{t.message}</td><td>{t.status}</td>
                    <td>
                      <button
                        className="btn"
                        onClick={async () => {
                          const reply = prompt('Enter admin reply:', t.admin_reply || 'Issue investigated and resolved.') || '';
                          await apiRequest(`/admin/tickets/${t.id}`, {
                            method: 'PATCH',
                            body: JSON.stringify({ status: 'resolved', admin_reply: reply }),
                          });
                          loadAll();
                        }}
                      >
                        Reply & Resolve
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'settings' && (
          <form
            className="card"
            style={{ maxWidth: 520 }}
            onSubmit={async (e) => {
              e.preventDefault();
              await apiRequest('/admin/settings', { method: 'PUT', body: JSON.stringify(platformSettings) });
              setStatusBanner('Platform economy & maintenance settings updated.');
              loadAll();
            }}
          >
            <h3>Coin Economy & Maintenance Mode</h3>
            <label>Daily Free Reward (Coins)</label>
            <input type="number" value={platformSettings.daily_reward_coins} onChange={(e) => setPlatformSettings({ ...platformSettings, daily_reward_coins: Number(e.target.value) })} />
            <label>Hourly Coin Rate (Coins / Hour)</label>
            <input type="number" value={platformSettings.coins_per_hour} onChange={(e) => setPlatformSettings({ ...platformSettings, coins_per_hour: Number(e.target.value) })} />
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
              <input
                type="checkbox"
                style={{ width: 'auto', margin: 0 }}
                checked={platformSettings.maintenance_mode}
                onChange={(e) => setPlatformSettings({ ...platformSettings, maintenance_mode: e.target.checked })}
              />
              Enable Global Maintenance Mode (blocks new user sessions)
            </label>
            <button className="btn" type="submit">Save Global Settings</button>
          </form>
        )}
      </main>
    </div>
  );
}
