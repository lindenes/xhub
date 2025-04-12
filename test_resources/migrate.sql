create table "user" (
	id UUID primary key not null,
	login text,
	email text not null,
	password text,
	is_author boolean,
	is_prime boolean,
	created_at timestamp
);

insert into "user" (id, email,  password) values ('a705baad-b3da-437d-8032-92b14afe87f2', 'test@gmail.com', 'd05484e251d330a22d08059994489cc1945c70d2badf47d2f3885fb2c3eaa3cc');

create table manga(
	id UUID primary key not null,
	name text not null,
	description text,
	created_at timestamp default now()
);

insert into manga (id, "name") values ('3396a57d-38c6-4bc3-ac50-6f8555c915dc', 'Тестовая манга');

create table tag(
	id serial primary key,
	name text
);

create table manga_tag(
	manga_id UUID references manga(id) on delete cascade,
	tag_id INTEGER references tag(id) on delete cascade,
	primary key (manga_id, tag_id)
);

CREATE TABLE manga_page (
	"oid" text NOT NULL,
	manga_id uuid NOT NULL,
	CONSTRAINT manga_page_pkey PRIMARY KEY (oid, manga_id)
);

create table manga_like (
    manga_id UUID references manga(id) on delete cascade,
	user_id UUID references "user"(id) on delete cascade,
	primary key (manga_id, user_id)
);

create table "comment" (
	id UUID primary key,
	manga_id UUID references manga(id) on delete cascade,
	user_id UUID references "user"(id) on delete cascade,
	content varchar(512),
	created_at timestamp default now()
);

insert into "comment" (id, manga_id, user_id, content) values ('a84bf6a2-fa92-4b40-8a8f-040c0724d8b9', '3396a57d-38c6-4bc3-ac50-6f8555c915dc', 'a705baad-b3da-437d-8032-92b14afe87f2', 'Тестовый текст');
