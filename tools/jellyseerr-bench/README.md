# Jellyseerr bench

There is no public Jellyseerr to test against, and pointing the app at a
real one would mean handling somebody's password. This answers the four
calls the app makes.

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
| Severance | partly available (still requestable) |
| Game of Thrones | downloading |
| Inception | awaiting approval |
| Denis Villeneuve | a person — must never appear in results |

Requesting something already known returns **409**, which the app reads as
an answer ("already requested"), not a failure. Requesting without a
session returns **401**.

Point the app at `http://10.0.2.2:5055` from the Android emulator, or
`http://localhost:5055` from an Apple simulator.
