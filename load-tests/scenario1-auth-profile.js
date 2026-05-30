import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, sessionToken, authParams } from './lib.js';

export const options = {
  scenarios: {
    auth_profile: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 30 },
        { duration: '2m', target: 30 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
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

  const profile = http.get(`${BASE_URL}/me`, { ...headers, tags: { name: 'me' } });
  check(profile, { 'profile status is 200': (r) => r.status === 200 });

  const tracks = http.get(`${BASE_URL}/tracks?limit=30`, { ...headers, tags: { name: 'tracks' } });
  check(tracks, { 'tracks status is 200': (r) => r.status === 200 });

  sleep(0.5 + Math.random() * 0.5);
}
