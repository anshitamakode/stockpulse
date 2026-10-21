// Thin fetch wrapper. Errors carry the server's ProblemDetail message when there is one.
const BASE = '/api';

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await res.text();
  const body = text ? safeJson(text) : null;
  if (!res.ok) {
    const message = body?.detail || body?.title || `${res.status} ${res.statusText}`;
    const error = new Error(message);
    error.status = res.status;
    error.body = body;
    throw error;
  }
  return body;
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

const json = (method, body) => ({ method, body: body === undefined ? undefined : JSON.stringify(body) });

export const getProducts = () => request('/products');
export const placeOrder = (id, quantity) => request(`/products/${id}/orders`, json('POST', { quantity }));
export const restock = (id, quantity) => request(`/products/${id}/restock`, json('POST', { quantity }));
export const requestSuggestions = (id) => request(`/products/${id}/suggestions`, json('POST'));

export const getSuggestions = (statuses) =>
  request('/suggestions' + (statuses?.length ? `?status=${statuses.join(',')}` : ''));
export const decideSuggestion = (kind, id, status) =>
  request(`/suggestions/${kind.toLowerCase()}/${id}`, json('PATCH', { status }));

export const getStrategy = () => request('/strategy');
export const setStrategy = (strategy) => request('/strategy', json('PATCH', { strategy }));
