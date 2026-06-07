CREATE KEYSPACE IF NOT EXISTS pekko_projection 
WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };


-- 1. 建立書籤表 (Offset Store)
CREATE TABLE IF NOT EXISTS pekko_projection.offset_store (
  projection_name text,
  partition int,
  projection_key text,
  offset text,
  manifest text,
  last_updated timestamp,
  PRIMARY KEY ((projection_name, partition), projection_key)
);

-- 2. 建立管理表 (Projection Management)
CREATE TABLE IF NOT EXISTS pekko_projection.projection_management (
  projection_name text,
  partition int,
  projection_key text,
  paused boolean,
  last_updated timestamp,
  PRIMARY KEY ((projection_name, partition), projection_key)
);