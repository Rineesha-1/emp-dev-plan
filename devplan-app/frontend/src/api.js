let keycloak;

export function setKeycloak(kc) {
  keycloak = kc;
}

/** Calls the backend with a fresh Keycloak token; throws an Error with the backend's message on failure. */
export async function api(method, path, body) {
  await keycloak.updateToken(30);
  const response = await fetch('/api' + path, {
    method,
    headers: { Authorization: 'Bearer ' + keycloak.token, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(data?.error || response.status + ' ' + response.statusText);
  }
  return data;
}
