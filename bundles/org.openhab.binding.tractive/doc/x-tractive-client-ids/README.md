# Known `x-tractive-client` values

Every Tractive API request must include an `x-tractive-client` header.
There is no official developer portal, so there's no single "correct" value to register for — the ones below were each independently obtained by unaffiliated developers.

All four are 24-character lowercase hex strings — the same format as MongoDB's default `ObjectId` primary key, and the same shape as the handful of other opaque identifiers observed elsewhere in the Tractive API (a pet ID, a couple of zone IDs, the `client_id` returned by `POST /auth/token` itself).
The sample backing that observation is small — only a few non-tracker IDs have actually been captured — but it at least supports the hypothesis that Tractive's backend treats "client" as just another database record, one per registered app, rather than a single hardcoded platform constant.

| Value                      | Used by                                                                                                                                                                                                                          | Source                                                                  | Last verified               |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | --------------------------- |
| `625e533dc3c3b41c28a669f0` | This binding (`API_CLIENT_ID` in `TractiveBindingConstants.java`), adopted from [zhulik/aiotractive](https://github.com/zhulik/aiotractive) (PyPI `aiotractive`)                                                                 | `aiotractive/api.py`                                                    | 2026-08-01 (live auth test) |
| `6536c228870a3c8857d452e8` | [FAXES/tractive](https://github.com/FAXES/tractive) (npm `tractive`)                                                                                                                                                             | `index.js`                                                              | 2026-08-01 (live auth test) |
| `6863b6545d3ac2bd948147db` | boerner-unofficial-tractive-rest-api (npm `tractive-rest-api`)                                                                                                                                                                   | `src/constants.ts` — no public repository URL in its own `package.json` | 2026-08-01 (live auth test) |
| `5f9be055d8912eb21a4cd7ba` | [xXBJXx/ioBroker.tractive-gps](https://github.com/xXBJXx/ioBroker.tractive-gps) and its community fork [iobroker-community-adapters/ioBroker.tractive-gps](https://github.com/iobroker-community-adapters/ioBroker.tractive-gps) | `src/main.ts` (identical value in both)                                 | 2026-08-01 (live auth test) |

## Thoughts

It never hurts to have more working keys.

Possibly Tractive's HTTP 429 rate limiting is keyed by `client_id` (as opposed to purely by source IP/host).
If it is, this list is a documented, confirmed-working fallback pool: if the binding's `client_id` were ever specifically throttled or flagged, three other known-working values are already here, without re-sniffing traffic from scratch.

Confirmed 2026-08-01, via a live test against `POST /auth/token`: Tractive's backend does enforce `client_id` against a real registry — it is not merely logged and ignored.
All four values above returned HTTP 200 with a valid `access_token`.
Two randomly-generated 24-character hex strings and one deliberately malformed value (too short to be a real ObjectId) all returned HTTP 403.

## A valid `client_id` is not a full-access `client_id`

Authenticating successfully is necessary but not sufficient for every endpoint.
`POST tracker/{trackerId}/request_status_update` (force an immediate tracker check-in) returns `403 {"code": 8006, "category": "SECURITY", "message": "Access to this resource has been denied."}` for all four values above, while the official Tractive app's own registered client is permitted (it reaches a per-resource `429` rate limit instead).
So the backend evidently attaches a permission scope to each registered client, not just a valid/invalid flag.

This does not affect the binding: every endpoint it actually calls works with `625e533dc3c3b41c28a669f0` and — per the auth test above — with the other three.
The caveat matters only if a future feature needs an endpoint that turns out to be first-party-app-only.

_Client survey found 2026-08-01; the `request_status_update` scope limit tested 2026-09-06/07._
