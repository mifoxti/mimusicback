import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, sessionToken, authParams } from './lib.js';

/** Ресурсоёмкий сценарий: скоринг рекомендаций + жанры + метаданные треков (аналог ИИ-модуля Englio). */
export const options = {
  scenarios: {
    recommendations: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 15 },
        { duration: '3m30s', target: 15 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  const token = sessionToken();
  if (!token) {
    sleep(1);
    return;
  }
  const headers = authParams(token);

  const rec = http.get(`${BASE_URL}/recommendations/tracks?limit=30`, {
    ...headers,
    tags: { name: 'recommendations' },
  });
  check(rec, {
    'recommendations status is 200': (r) => r.status === 200,
    'recommendations is json array': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body);
      } catch (_) {
        return false;
      }
    },
  });

  sleep(1 + Math.random());
}
