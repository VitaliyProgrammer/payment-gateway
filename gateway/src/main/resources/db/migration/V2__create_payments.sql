-- Гроші зберігаються як bigint у мінімальних одиницях (наприклад, копійках/центах),
-- ніколи не як тип із плаваючою комою і ніколи як decimal без фіксованого масштабу -
-- тож питання округлення тут в принципі виникнути не може.
--
-- captured_amount і refunded_amount - це денормалізовані підсумкові суми, а не
-- значення, які щоразу рахуються через SUM() по таблиці операцій. На стадії 4
-- буде забезпечено, під конкурентним навантаженням, що refunded_amount ніколи не
-- перевищить captured_amount - сам по собі check-констрейнт цього виразити не
-- може (це залежить від відносного порядку конкурентних оновлень), тож інваріант
-- забезпечується в коді застосунку під захистом колонки version нижче.
-- Check-констрейнти тут лише обмежують значення, які кожна колонка може мати сама
-- по собі.
create table payments (
    id                uuid         primary key,
    merchant_id       uuid         not null references merchants (id),
    amount            bigint       not null,
    captured_amount   bigint       not null default 0,
    refunded_amount   bigint       not null default 0,
    currency          varchar(3)   not null,
    status            text         not null,
    -- Оптимістичне блокування: кожен перехід стану (авторизація, захоплення,
    -- скасування, повернення) повинен пройти цикл читання-перевірки-запису цієї
    -- колонки, тож дві конкурентні спроби переходу над одним платежем ніколи не
    -- зможуть обидві мовчки успішно завершитись - той, хто програв, отримає
    -- помилку застарілої версії замість того, щоб мовчки перезаписати переможця.
    version           bigint       not null default 0,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),

    constraint payments_amount_positive check (amount > 0),
    constraint payments_captured_amount_range check (captured_amount >= 0 and captured_amount <= amount),
    constraint payments_refunded_amount_range check (refunded_amount >= 0 and refunded_amount <= captured_amount),
    constraint payments_status_valid check (status in (
        'CREATED', 'PROCESSING', 'AUTHORIZED', 'CAPTURED',
        'PARTIALLY_REFUNDED', 'REFUNDED', 'DECLINED', 'CANCELED',
        'FAILED', 'NEEDS_RECONCILIATION'
    ))
);

-- Кожен пошук платежу і кожен запит "список платежів цього мерчанта" спершу
-- фільтрує за merchant_id, тому індекс потрібен, щоб обидва сценарії не
-- перетворились на послідовне сканування, коли таблиця виросте до мільйонів рядків.
create index payments_merchant_id_idx on payments (merchant_id);
