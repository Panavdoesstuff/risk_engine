// k6 load test for POST /orders: the API only (JWT check, Redis rate limit and replay guard, Postgres
// PENDING row, write to Redpanda, 202). It says nothing about the shards; see ./gradlew :loadtest:scale.
//
//   scripts/k6-orders.sh            (brings up its own compose stack with synthetic data and runs this)
//
// 100 traders, each with their own token and account, sending 1,000 orders/s in total for 60 s: on average
// exactly each user's rate limit (10/s, burst 20), so the 429 rate shows how bursty arrivals meet the bucket.
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const API = __ENV.API || 'http://api:8080';
const SECRET = __ENV.JWT_SECRET;
const USERS = parseInt(__ENV.USERS || '100');
const RATE = parseInt(__ENV.RATE || '1000');
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = (__ENV.ACCOUNTS || '').split(',').filter((a) => a.length > 0);
const INSTRUMENT = __ENV.INSTRUMENT || 'NIFTY-20240613-25000-CE';

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 200,
      maxVUs: 600,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(99)', 'max'],
};

const accepted = new Counter('orders_accepted_202');
const limited = new Counter('orders_rate_limited_429');
const other = new Counter('orders_other_status');
const limitedRate = new Rate('rate_limited');
const acceptedLatency = new Trend('latency_202', true);

function b64url(s) {
  return encoding.b64encode(s, 'rawurl');
}

// An HS256 token exactly like the API's own: iss, sub, role, account, iat, exp.
function token(user, account) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256' }));
  const claims = b64url(JSON.stringify({ iss: 'risk-engine', sub: user, role: 'trader', account: account, iat: now, exp: now + 3600 }));
  const signature = crypto.hmac('sha256', SECRET, `${header}.${claims}`, 'base64rawurl');
  return `${header}.${claims}.${signature}`;
}

export function setup() {
  if (!SECRET) throw new Error('JWT_SECRET is required');
  if (ACCOUNTS.length < USERS) throw new Error(`need ${USERS} accounts, got ${ACCOUNTS.length}`);
  const users = [];
  for (let i = 0; i < USERS; i++) users.push({ name: `k6-user-${i}`, account: ACCOUNTS[i], token: token(`k6-user-${i}`, ACCOUNTS[i]) });
  return { users };
}

export default function (data) {
  // Spread requests over the users round-robin by iteration, so each sees about RATE / USERS per second.
  const u = data.users[(__ITER + __VU * 7919) % data.users.length];
  const res = http.post(`${API}/orders`, JSON.stringify({ accountId: u.account, instrumentId: INSTRUMENT, side: 'BUY', qty: 25 }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${u.token}`,
      'X-Request-Id': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
    tags: { name: 'POST /orders' },
  });
  limitedRate.add(res.status === 429);
  if (res.status === 202) {
    accepted.add(1);
    acceptedLatency.add(res.timings.duration);
  } else if (res.status === 429) {
    limited.add(1);
  } else {
    other.add(1);
  }
}
