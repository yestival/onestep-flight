ALTER TABLE mobile_installation ADD COLUMN platform varchar(255) NOT NULL DEFAULT 'ios';
ALTER TABLE mobile_installation ADD COLUMN push_provider varchar(255) NOT NULL DEFAULT 'apns';
ALTER TABLE mobile_installation ALTER COLUMN device_token TYPE varchar(4096);
ALTER TABLE mobile_push_delivery ALTER COLUMN token TYPE varchar(4096);
