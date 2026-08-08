# Jellyseerr bench

There is no public Jellyseerr to test against, and pointing the app at a
real one would mean handling somebody's password. This answers the calls
the app makes.

```bash
python3 fake_jellyseerr.py 5055
```

Sign in with **any username** and the password **`bench`**; anything else
gets a 401, so the failure path is testable too.

Fixtures cover every state the UI has to render:

| Title | State |
|---|---|
| Dune: Part Two | requestable |
| Dune | available |
| Sicario | downloading — a film's progress bar |
| Severance | partly available: S1 available, S2 requestable |
| Game of Thrones | downloading — three episode grabs at once |
| Inception | awaiting approval |
| Denis Villeneuve | a person — must never appear in results |

Requesting something already known returns **409**, which the app reads as
an answer ("already requested"), not a failure. Requesting without a
session returns **401**.

## The two things the OpenAPI spec does not mention

Both are on the wire, and the app depends on both.

**`mediaInfo.downloadStatus`** is what Sonarr and Radarr are fetching.
Progress here is computed from wall-clock, so a poll loop shows a bar that
**actually moves** rather than a frozen number — Sicario finishes in about
3 minutes, Game of Thrones' three grabs are staggered and finish between 3
and 7. A grab that has not started yet reports **no `timeLeft` at all**,
which is exactly how a stalled download looks, so the "no estimate" path
gets exercised for free.

**`mediaInfo.seasons`** is **sparse** — it holds only the seasons somebody
has touched, not one entry per season. Severance ships with season 1
available and season 2 simply absent from that list, which is the case the
season picker and the end-of-season prompt both exist for.

## Per-season requests

`POST /api/v1/request` reads `seasons`, which is either the string `"all"`
or an array of season **numbers**. `"all"` excludes season 0 — Severance's
specials are in `GET /api/v1/tv/95396` precisely so that asymmetry is
visible.

Seasons already spoken for are dropped silently and the request succeeds
with the remainder; when nothing is left it answers **202 "No seasons
available to request"** — not 409, and not an error code at all.

That 202 is the sharpest trap in this API. `NoSeasonsAvailableError` is
answered with `status: 202` in both the Overseerr and the Seerr sources,
so a client that reads "2xx means sent" tells people their season is on
the way when nothing was created. The bench answers 202 precisely so
that client fails here instead of in someone's living room.

## Things actually arrive

A download that runs out of time makes its title **available**, which is
the moment the arrival notice exists for. Sicario lands about 3 minutes
in, Game of Thrones between 3 and 7.

Three minutes is a long time to wait for one toast:

```bash
curl -X POST localhost:5055/api/v1/bench/land/242582
```

That marks the title, and every season anybody asked for, as available
right now.

**Landing only part of a show** is the case the notice must stay *quiet*
for — a show where one season arrives and another has not is partly
available, not available, and "it has arrived" would be a lie:

```bash
curl -X POST "localhost:5055/api/v1/request" -H "Cookie: connect.sid=bench-session" \
  -H "Content-Type: application/json" -d '{"mediaType":"tv","mediaId":1399,"seasons":[3]}'
curl -X POST "localhost:5055/api/v1/bench/land/1399?seasons=2"
```

The title comes back `status: 4`, and nothing should be announced.

## State, and getting it back

Every request mutates the fixtures in place, so after one E2E run the
table above no longer holds:

```bash
curl -X POST localhost:5055/api/v1/bench/reset
```

Point the app at `http://10.0.2.2:5055` from the Android emulator, or
`http://localhost:5055` from an Apple simulator.

Severance's TMDb id (95396) is the one the **subtitle bench**'s show
reports, so the two benches line up: finish the last episode there and the
season offered here is the one that is missing.
