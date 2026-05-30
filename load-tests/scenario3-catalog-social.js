import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, sessionToken, authParams } from './lib.js';

export const options = {
  scenarios: {
    catalog_social: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 30 },
        { duration: '3m30s', target: 30 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const token = sessionToken();
  if (!token) {
    sleep(1);
    return;
  }
  const headers = authParams(token);

  const tracks = http.get(`${BASE_URL}/tracks?limit=20`, { ...headers, tags: { name: 'tracks' } });
  check(tracks, { 'tracks status is 200': (r) => r.status === 200 });

  const friends = http.get(`${BASE_URL}/friends`, { ...headers, tags: { name: 'friends' } });
  check(friends, { 'friends status is 200': (r) => r.status === 200 });

  const unread = http.get(`${BASE_URL}/notifications/unread-count`, {
    ...headers,
    tags: { name: 'notifications' },
  });
  check(unread, { 'notifications status is 200': (r) => r.status === 200 });

  sleep(0.3 + Math.random() * 0.7);
}
