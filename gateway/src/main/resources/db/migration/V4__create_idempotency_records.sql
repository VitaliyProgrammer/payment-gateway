-- Ключ ідемпотентності гарантує, що клієнт, який повторює той самий запит
-- (мережевий таймаут, подвійний клік, ретрай мобільного застосунку), ніколи не
-- створить платіж вдруге. merchant_id входить в унікальність разом із ключем,
-- бо кожен мерчант генерує ключі самостійно - без цього один мерчант міг би
-- випадково "зайняти" ключ, яким уже скористався інший.
create table idempotency_records (
    id                   uuid         primary key,
    merchant_id          uuid         not null references merchants (id),
    idempotency_key      text         not null,
    -- Хеш тіла запиту (сума + валюта). Дозволяє відрізнити легітимний повтор
    -- того самого запиту від помилки клієнта, який випадково переюзав той
    -- самий ключ для іншого платежу - другий випадок має бути відхилений, а
    -- не мовчки повернути перший платіж.
    request_fingerprint  text         not null,
    status               text         not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    response_status      integer,
    response_body        text,
    created_at           timestamptz  not null default now(),
    completed_at         timestamptz,

    constraint idempotency_records_response_present_when_completed
        check (status <> 'COMPLETED' or (response_status is not null and response_body is not null))
);

-- Саме цей унікальний індекс вирішує гонку: коли 200 запитів з однаковим ключем
-- намагаються вставити рядок одночасно, рівно один INSERT переможе, а решта
-- отримають порушення унікальності від самої бази даних - без потреби у
-- зовнішньому розподіленому локу чи серіалізації на рівні застосунку.
create unique index idempotency_records_merchant_key_idx on idempotency_records (merchant_id, idempotency_key);
