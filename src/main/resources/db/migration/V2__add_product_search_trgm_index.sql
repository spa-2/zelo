create extension if not exists pg_trgm;
create index if not exists idx_products_name_trgm on products using gin (name gin_trgm_ops);
create index if not exists idx_products_brand_trgm on products using gin (brand gin_trgm_ops);
