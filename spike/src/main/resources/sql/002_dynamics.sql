-- Динамика памяти (канон 17 §5): опытное время = count(DISTINCT session_id),
-- пулы интерференции = scope, единая кривая с несгораемым минимумом.
CREATE OR REPLACE FUNCTION fact_weight(lambda DOUBLE PRECISION, w_floor DOUBLE PRECISION, d_experience INT)
RETURNS DOUBLE PRECISION LANGUAGE sql IMMUTABLE AS $$
    SELECT w_floor + (1.0 - w_floor) * exp(-lambda * d_experience)
$$;

-- TODO(Р17/§5): уточнить семантику Δопыт для прогона (расстояние между подтверждениями)
CREATE OR REPLACE VIEW v_fact_experience AS
SELECT f.id,
       f.owner_id,
       f.scope,
       count(DISTINCT other.session_id) AS d_experience
FROM facts f
JOIN facts other
  ON other.owner_id = f.owner_id
 AND other.scope = f.scope
 AND other.created_at <= f.created_at
GROUP BY f.id, f.owner_id, f.scope;
