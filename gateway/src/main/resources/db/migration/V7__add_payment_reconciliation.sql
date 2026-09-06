-- Стадія 6: примирення (reconciliation). Коли виклик до еквайра завершується
-- таймаутом читання або 5xx, шлюз НЕ знає, чи авторизація відбулась - запит
-- пішов, відповідь загубилась. Позначити такий платіж FAILED було б брехнею
-- (гроші могли бути заблоковані), а сліпий ретрай authorize міг би списати
-- вдруге. Тому платіж переходить у NEEDS_RECONCILIATION (цей стан закладений у
-- payments_status_valid ще з V2), а фоновий sweeper пізніше ПИТАЄ еквайра про
-- підсумок замість того, щоб заряджати повторно.

-- Скільки разів sweeper уже намагався дізнатись підсумок по цьому платежу.
-- Обмежує нескінченні спроби: після payflow.reconciliation.max-attempts платіж
-- чесно позначається FAILED з гучним логом.
alter table payments add column reconcile_attempts integer not null default 0;

-- Коли платіж знову доступний для спроби примирення. Виконує подвійну роль -
-- і backoff між невдалими спробами достукатись до еквайра, і "оренду" на час
-- активної спроби (відсувається в майбутнє одразу при захопленні рядка
-- sweeper-ом, ще ДО HTTP-виклику). Якщо sweeper впаде посеред спроби, оренда
-- мине сама і рядок підхопить інший - точнісінько як next_attempt_at в outbox
-- (V6). NULL для платежів, яких примирення не стосується.
alter table payments add column reconcile_next_at timestamptz;

alter table payments add column last_reconcile_error text;

-- Частковий індекс лише на рядках, які реально чекають на примирення: sweeper
-- фільтрує саме по (status = 'NEEDS_RECONCILIATION', reconcile_next_at <= now()).
create index payments_reconcile_due_idx on payments (reconcile_next_at)
    where status = 'NEEDS_RECONCILIATION';

-- Другий шлях, яким платіж потрапляє до sweeper-а: застряг у PROCESSING, бо
-- воркер (чи цілий інстанс) помер посеред виклику до еквайра і вже нікого не
-- лишилось, хто довів би цей платіж до кінця. Sweeper підбирає такі рядки за
-- віком updated_at.
create index payments_stale_processing_idx on payments (status, updated_at)
    where status = 'PROCESSING';
