import fs from 'fs';
import path from 'path';
import { dbPool } from '../config/database.js';

async function runMigrations() {
  const migrationsDir = path.resolve(process.cwd(), '../database/migrations');
  const files = fs.readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort();
  for (const file of files) {
    const sql = fs.readFileSync(path.join(migrationsDir, file), 'utf8');
    console.log(`Applying migration: ${file}`);
    await dbPool.query(sql);
  }
  console.log('All PostgreSQL migrations applied successfully.');
  await dbPool.end();
}

runMigrations().catch((err) => {
  console.error('Migration failed:', err);
  process.exit(1);
});
