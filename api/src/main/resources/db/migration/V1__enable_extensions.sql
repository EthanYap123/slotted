-- btree_gist lets the booking exclusion constraint combine room equality with range overlap (ADR-0001).
CREATE EXTENSION IF NOT EXISTS btree_gist;
-- citext gives case-insensitive emails without lower() everywhere (docs/ERD.md).
CREATE EXTENSION IF NOT EXISTS citext;
