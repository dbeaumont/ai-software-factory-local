import test from 'node:test';
import assert from 'node:assert/strict';
import { total } from '../src/checkout.js';

test('totals line items', () => {
  assert.equal(total([{ price: 12 }, { price: 8 }]), 20);
});

test('rejects negative prices', () => {
  assert.throws(() => total([{ price: -1 }]), /non-negative price/);
});
