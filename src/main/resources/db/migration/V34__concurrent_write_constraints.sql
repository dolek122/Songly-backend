-- ADR 0052: dwa ograniczenia, których dotąd pilnował wyłącznie kod sprawdzający
-- stan przed zapisem. Równoległe żądania mijały to sprawdzenie, bo żadne nie
-- widziało jeszcze wiersza drugiego.

-- Zaproszenia do znajomych: jedno oczekujące na parę, w którąkolwiek stronę.
-- Zaproszenie w drugą stronę kod zamienia w znajomość (SocialGraphService), więc
-- dwa oczekujące w obie strony mógł zostawić tylko wyścig. Takie pary kończymy
-- tak, jak skończyłyby się po kolei: znajomością i oboma zaproszeniami
-- przyjętymi. Bez tego indeksu poniżej nie da się założyć.
INSERT INTO friendships (user_a_id, user_b_id, created_at)
SELECT DISTINCT LEAST(a.requester_id, a.addressee_id), GREATEST(a.requester_id, a.addressee_id), now()
FROM friend_requests a
JOIN friend_requests b ON b.requester_id = a.addressee_id AND b.addressee_id = a.requester_id
WHERE a.status = 'PENDING' AND b.status = 'PENDING'
ON CONFLICT DO NOTHING;

UPDATE friend_requests r
SET status = 'ACCEPTED', responded_at = now()
WHERE r.status = 'PENDING'
  AND EXISTS (SELECT 1 FROM friend_requests o
              WHERE o.requester_id = r.addressee_id AND o.addressee_id = r.requester_id
                AND o.status = 'PENDING');

-- Indeks kierunkowy z V5 zostaje: obsługuje wyszukiwanie zaproszenia w danym
-- kierunku, a ten nowy jest od niego szerszy.
CREATE UNIQUE INDEX uq_friend_requests_pending_pair
    ON friend_requests (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id))
    WHERE status = 'PENDING';

-- Nazwa społeczności unikalna bez względu na wielkość liter, jak sprawdza ją kod
-- (existsByNameIgnoreCase). Ograniczenie z V6 rozróżniało wielkość liter, więc
-- "Dup" i "dup" wysłane naraz powstawały obie. Istniejące takie pary dostają
-- przyrostek, starsza zachowuje nazwę. 90 znaków zostawia miejsce na przyrostek
-- w limicie 100.
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY lower(name) ORDER BY created_at, id) AS rn
    FROM communities
)
UPDATE communities c
SET name = left(c.name, 90) || ' (' || r.rn || ')'
FROM ranked r
WHERE c.id = r.id AND r.rn > 1;

ALTER TABLE communities DROP CONSTRAINT communities_name_key;
CREATE UNIQUE INDEX uq_communities_name_lower ON communities (lower(name));
