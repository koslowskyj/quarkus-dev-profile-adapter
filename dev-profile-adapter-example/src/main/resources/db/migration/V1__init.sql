create table widget (
    id   serial primary key,
    name text not null
);

insert into widget (name) values ('first widget');
