CREATE TABLE IF NOT EXISTS rooms (
  room_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  visibility TEXT NOT NULL,
  member_count INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rooms_public_updated ON rooms(visibility, updated_at DESC);
