import bcrypt from 'bcryptjs';
import { dbPool } from '../config/database.js';

async function seedAdmin() {
  const email = process.env.ADMIN_EMAIL || 'admin@shubhankarcloud.local';
  const password = process.env.ADMIN_PASSWORD || 'ChangeMeAdmin123!';
  const username = 'ShubhankarAdmin';
  const hash = await bcrypt.hash(password, 12);

  await dbPool.query(
    `INSERT INTO users (username, email, password_hash, role, coin_balance)
     VALUES ($1, $2, $3, 'admin', 100)
     ON CONFLICT (email) DO UPDATE SET role = 'admin', password_hash = EXCLUDED.password_hash`,
    [username, email.toLowerCase(), hash]
  );

  console.log(`Admin user provisioned: ${email}`);
  await dbPool.end();
}

seedAdmin().catch((err) => {
  console.error('Admin seed failed:', err);
  process.exit(1);
});
