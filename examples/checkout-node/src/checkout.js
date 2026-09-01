export function total(items) {
  if (!Array.isArray(items)) throw new TypeError('items must be an array');
  return items.reduce((sum, item) => {
    if (!item || !Number.isFinite(item.price) || item.price < 0) {
      throw new TypeError('each item must have a non-negative price');
    }
    return sum + item.price;
  }, 0);
}
