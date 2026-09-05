-- Мерчант - це клієнт API шлюзу: інтернет-магазин, який надсилає платежі за
-- допомогою API-ключа й отримує вебхуки про їх результат. Усе інше в схемі буде
-- посилатись на цю таблицю, тож вона йде першою.

create table merchants (
    id           uuid         primary key,
    name         text         not null,
    -- Зберігається лише хеш. Сам ключ показується мерчанту один раз, при
    -- створенні, і відновити його потім неможливо - з тієї ж причини, з якої
    -- паролі ніколи не зберігають у відкритому вигляді.
    api_key_hash text         not null,
    -- Куди доставляти вебхуки про платежі. Nullable: мерчант може інтегруватись
    -- через опитування GET /v1/payments/{id} замість отримання колбеків.
    webhook_url  text,
    active       boolean      not null default true,
    created_at   timestamptz  not null default now(),

    constraint merchants_name_not_blank check (length(btrim(name)) > 0)
);

-- Кожен автентифікований запит шукає мерчанта саме за цим хешем, тому він
-- індексований. Унікальний, бо два мерчанти з однаковим ключем зробили б запити
-- неоднозначними.
create unique index merchants_api_key_hash_key on merchants (api_key_hash);
