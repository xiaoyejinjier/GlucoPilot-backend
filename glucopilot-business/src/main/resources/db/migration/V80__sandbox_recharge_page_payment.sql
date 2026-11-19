ALTER TABLE wallet_recharge_order
    ADD COLUMN payment_mode VARCHAR(8) NOT NULL DEFAULT 'QR'
    COMMENT '沙箱付款方式 QR扫码 PAGE网页账号付款';
