# Local identity provider

`roomlock-realm.json` is imported by the Keycloak container of `infra/docker-compose.yml` on every
start (`start-dev --import-realm`). It exists so a developer and the Gatling simulations get real
tokens out of a real OIDC flow instead of a hand-rolled one — booking-service validates an RS256
access token against this realm's JWK set and mints nothing itself.

It is **local only**. Nothing in `infra/gcp` runs Keycloak, and a deployment points
`OIDC_ISSUER_URI` and `OIDC_JWK_SET_URI` at its own provider — see
[deployment](../../docs/deployment.md). Every credential in the file is a throwaway of this stack and
`sslRequired` is `none`, because the stack speaks plain HTTP on localhost. Those two facts together
are exactly why this file must never be the starting point of anything reachable from outside a
laptop.

## What is in it

The API has two levels of access (`ApiSecurityConfig`), so the realm has two clients.

| Client | Flow | Scope | What it is for |
| --- | --- | --- | --- |
| `roomlock-guest` | public, direct access grants (password) | none | A guest: holds a room, pays for it, cancels it. Deliberately cannot write the catalog. |
| `roomlock-operator` | confidential, client credentials (secret `roomlock-operator-secret`) | `catalog:write` | Creating a hotel and adding rooms — and the client the load tests authenticate as, because a run needs the scope to build its fixture and a subject to book with in one token. |

One user, `guest` / `guest`, with a fixed id `11111111-1111-4111-8111-111111111111`. The id is fixed
rather than generated because it is the token subject, and the token subject is the guest id every
booking of a local run carries — a stable one makes `guestId` in a response something you can
recognise instead of a fresh UUID after every `down -v`.

`README.md` in the repository root has the two commands that fetch a token.

## Both addresses of the same realm

`KC_HOSTNAME` pins the frontend URL to `http://localhost:${KEYCLOAK_PORT}`, so every token carries
that one `iss` whoever asked for it. booking-service validates `iss` against it and fetches the keys
from `http://keycloak:8080/...` instead, which is the same realm at the address a container can
actually reach. Without the pin, a token fetched from the host and a token fetched from inside the
network would carry different issuers and one of the two would be refused.

## Changing it

Edit the file and recreate the container: `docker compose -f infra/docker-compose.yml up -d
--force-recreate keycloak`. The import strategy is Keycloak's default `IGNORE_EXISTING`, so an
existing realm is left alone — `docker compose down -v` (or deleting the container) is what makes a
changed realm take effect. Nothing in the repository reads the realm through the admin API, so there
is no export step to keep in sync.
