ALTER TABLE T_PASSWORD ADD COLUMN username VARCHAR(30) NOT NULL;
CREATE INDEX I_PASSWORD__username ON T_PASSWORD(username);
