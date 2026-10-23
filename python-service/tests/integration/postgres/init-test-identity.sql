CREATE ROLE blockcommander_b2t
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
    PASSWORD 'ck03-b2t-disposable-only-5f4b53b6';

ALTER DATABASE blockcommander_b2t OWNER TO blockcommander_b2t;

CREATE TABLE public.blockcommander_test_identity (
    identity uuid PRIMARY KEY,
    compose_project text NOT NULL,
    purpose text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO public.blockcommander_test_identity(identity, compose_project, purpose)
VALUES (
    '5f4b53b6-2c82-4d68-b788-7860daf2b70f',
    'blockcommander-ck03-b2t',
    'disposable-postgresql-integration'
);

ALTER TABLE public.blockcommander_test_identity OWNER TO blockcommander_b2t;
GRANT SELECT ON public.blockcommander_test_identity TO blockcommander_b2t;
