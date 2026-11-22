import assert from 'node:assert/strict';
import test from 'node:test';
import { calculateCoinsForHours, calculateUsedCoins } from '../src/services/coinService.js';

test('calculateCoinsForHours matches exact 10 coins = 1h and 20 coins = 2h policy', () => {
  assert.equal(calculateCoinsForHours(1), 10);
  assert.equal(calculateCoinsForHours(2), 20);
  assert.throws(() => calculateCoinsForHours(0));
  assert.throws(() => calculateCoinsForHours(-1));
});

test('calculateUsedCoins settles partial minutes without exceeding reserved coins or negative refund', () => {
  const full = calculateUsedCoins(60, 10);
  assert.equal(full.coinsCharged, 10);
  assert.equal(full.coinsRefunded, 0);

  const half = calculateUsedCoins(30, 10);
  assert.equal(half.coinsCharged, 5);
  assert.equal(half.coinsRefunded, 5);

  const twoHoursEarly = calculateUsedCoins(60, 20);
  assert.equal(twoHoursEarly.coinsCharged, 10);
  assert.equal(twoHoursEarly.coinsRefunded, 10);
});
