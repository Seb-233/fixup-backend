-- Usuario de ejecución equivalente al runtime de Green (ops/blue-green): las migraciones las aplica
-- otro usuario (el Job de migración, dueño de las tablas) y el backend se conecta con un rol que no
-- es superusuario, no es dueño de nada, no tiene DDL ni BYPASSRLS y solo es miembro de fixup_app.
-- V10 crea fixup_app solo si no existe, así que aquí se crea antes para poder otorgarlo.
CREATE ROLE fixup_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE fixup_runtime LOGIN PASSWORD 'runtime-it' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT fixup_app TO fixup_runtime;
