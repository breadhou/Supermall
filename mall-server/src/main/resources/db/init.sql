-- =============================================
-- 电商平台数据库初始化脚本
-- =============================================

CREATE DATABASE IF NOT EXISTS mall
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE mall;

-- =============================================
-- 1. 用户域
-- =============================================

CREATE TABLE user (
    id          BIGINT       NOT NULL COMMENT 'PK，雪花算法',
    username    VARCHAR(64)  NOT NULL COMMENT '用户名',
    password    VARCHAR(256) NOT NULL COMMENT 'BCrypt 加密',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    email       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE address (
    id          BIGINT       NOT NULL COMMENT 'PK',
    user_id     BIGINT       NOT NULL COMMENT 'FK → user.id',
    receiver    VARCHAR(64)  NOT NULL COMMENT '收件人',
    phone       VARCHAR(20)  NOT NULL COMMENT '联系电话',
    province    VARCHAR(64)  NOT NULL COMMENT '省',
    city        VARCHAR(64)  NOT NULL COMMENT '市',
    district    VARCHAR(64)  NOT NULL COMMENT '区',
    detail      VARCHAR(256) NOT NULL COMMENT '详细地址',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '1=默认地址',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址';

-- =============================================
-- 2. 商品域
-- =============================================

CREATE TABLE category (
    id          BIGINT      NOT NULL COMMENT 'PK',
    name        VARCHAR(64) NOT NULL COMMENT '分类名称',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID，0=顶级',
    level       TINYINT     NOT NULL COMMENT '层级 1~3',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

CREATE TABLE product (
    id          BIGINT       NOT NULL COMMENT 'PK',
    name        VARCHAR(256) NOT NULL COMMENT '商品名称',
    description TEXT         DEFAULT NULL COMMENT '商品描述',
    category_id BIGINT       NOT NULL COMMENT 'FK → category.id',
    merchant_id BIGINT       NOT NULL COMMENT 'FK → merchant.id',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ON_SHELF/OFF_SHELF',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_merchant_id (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU';

CREATE TABLE product_sku (
    id          BIGINT        NOT NULL COMMENT 'PK',
    product_id  BIGINT        NOT NULL COMMENT 'FK → product.id',
    specs       VARCHAR(512)  DEFAULT NULL COMMENT '规格JSON，如 {"颜色":"黑","存储":"256G"}',
    price       DECIMAL(10,2) NOT NULL COMMENT '价格',
    stock       INT           NOT NULL DEFAULT 0 COMMENT '日常库存',
    image       VARCHAR(512)  DEFAULT NULL COMMENT 'SKU图片',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU';

CREATE TABLE review (
    id          BIGINT        NOT NULL COMMENT 'PK',
    user_id     BIGINT        NOT NULL COMMENT 'FK → user.id',
    product_id  BIGINT        NOT NULL COMMENT 'FK → product.id',
    order_id    BIGINT        NOT NULL COMMENT 'FK → order.id（买过才能评）',
    rating      TINYINT       NOT NULL COMMENT '1~5 评分',
    content     VARCHAR(1024) DEFAULT NULL COMMENT '评价内容',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_product_id (product_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品评价';

-- =============================================
-- 3. 交易域
-- =============================================

CREATE TABLE cart_item (
    id        BIGINT NOT NULL COMMENT 'PK',
    user_id   BIGINT NOT NULL COMMENT 'FK → user.id',
    sku_id    BIGINT NOT NULL COMMENT 'FK → product_sku.id',
    quantity  INT    NOT NULL DEFAULT 1 COMMENT '数量',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sku (user_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

CREATE TABLE `order` (
    id           BIGINT        NOT NULL COMMENT 'PK',
    order_no     VARCHAR(32)   NOT NULL COMMENT '订单号，雪花算法',
    user_id      BIGINT        NOT NULL COMMENT 'FK → user.id',
    address_id   BIGINT        NOT NULL COMMENT '收货地址快照ID',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '实付金额',
    coupon_id    BIGINT        DEFAULT NULL COMMENT '使用的优惠券ID，可空',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PAID/SHIPPED/RECEIVED/REFUNDED/CANCELLED',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE order_item (
    id       BIGINT        NOT NULL COMMENT 'PK',
    order_id BIGINT        NOT NULL COMMENT 'FK → order.id',
    sku_id   BIGINT        NOT NULL COMMENT 'FK → product_sku.id',
    price    DECIMAL(10,2) NOT NULL COMMENT '下单时的价格快照',
    quantity INT           NOT NULL COMMENT '数量',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

CREATE TABLE refund (
    id        BIGINT        NOT NULL COMMENT 'PK',
    order_id  BIGINT        NOT NULL COMMENT 'FK → order.id',
    user_id   BIGINT        NOT NULL COMMENT 'FK → user.id',
    reason    VARCHAR(512)  NOT NULL COMMENT '退款原因',
    amount    DECIMAL(10,2) NOT NULL COMMENT '退款金额',
    status    VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/COMPLETED',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款表';

-- =============================================
-- 4. 促销域（秒杀 + 优惠券）
-- =============================================

CREATE TABLE seckill_activity (
    id         BIGINT      NOT NULL COMMENT 'PK',
    name       VARCHAR(128) NOT NULL COMMENT '活动名称',
    start_time DATETIME    NOT NULL COMMENT '开始时间',
    end_time   DATETIME    NOT NULL COMMENT '结束时间',
    status     VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED/IN_PROGRESS/ENDED',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_time (start_time, end_time),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';

CREATE TABLE seckill_item (
    id             BIGINT        NOT NULL COMMENT 'PK',
    activity_id    BIGINT        NOT NULL COMMENT 'FK → seckill_activity.id',
    sku_id         BIGINT        NOT NULL COMMENT 'FK → product_sku.id',
    seckill_price  DECIMAL(10,2) NOT NULL COMMENT '秒杀价格',
    stock          INT           NOT NULL COMMENT '秒杀专用库存',
    limit_per_user INT           NOT NULL DEFAULT 1 COMMENT '每人限购数量',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_activity_id (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品';

CREATE TABLE seckill_order (
    id              BIGINT NOT NULL COMMENT 'PK',
    user_id         BIGINT NOT NULL COMMENT 'FK → user.id',
    seckill_item_id BIGINT NOT NULL COMMENT 'FK → seckill_item.id',
    order_id        BIGINT NOT NULL COMMENT 'FK → order.id',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_seckill (user_id, seckill_item_id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单（一人一单防重）';

CREATE TABLE coupon (
    id          BIGINT        NOT NULL COMMENT 'PK',
    name        VARCHAR(128)  NOT NULL COMMENT '优惠券名称',
    type        VARCHAR(16)   NOT NULL COMMENT 'FULL_REDUCTION=满减 / DISCOUNT=折扣',
    discount    DECIMAL(10,2) NOT NULL COMMENT '满减金额或折扣比例',
    min_amount  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '最低消费金额',
    total       INT           NOT NULL COMMENT '发放总量',
    expire_day  INT           NOT NULL COMMENT '领取后N天过期',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板';

CREATE TABLE user_coupon (
    id        BIGINT      NOT NULL COMMENT 'PK',
    user_id   BIGINT      NOT NULL COMMENT 'FK → user.id',
    coupon_id BIGINT      NOT NULL COMMENT 'FK → coupon.id',
    status    VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
    used_at   DATETIME    DEFAULT NULL COMMENT '使用时间',
    created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_coupon_id (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券';

-- =============================================
-- 5. 其他表
-- =============================================

CREATE TABLE merchant (
    id         BIGINT      NOT NULL COMMENT 'PK',
    name       VARCHAR(128) NOT NULL COMMENT '商家名称',
    contact    VARCHAR(64)  DEFAULT NULL COMMENT '联系方式',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家';

CREATE TABLE admin_user (
    id         BIGINT       NOT NULL COMMENT 'PK',
    username   VARCHAR(64)  NOT NULL COMMENT '用户名',
    password   VARCHAR(256) NOT NULL COMMENT 'BCrypt 加密',
    role       VARCHAR(32)  NOT NULL DEFAULT 'ADMIN' COMMENT '角色：ADMIN/SUPER_ADMIN',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员';

CREATE TABLE payment_record (
    id       BIGINT        NOT NULL COMMENT 'PK',
    order_id BIGINT        NOT NULL COMMENT 'FK → order.id',
    amount   DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    method   VARCHAR(16)   NOT NULL DEFAULT 'SIMULATED' COMMENT '支付方式',
    status   VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    paid_at  DATETIME      DEFAULT NULL COMMENT '支付完成时间',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付记录';

CREATE TABLE logistics (
    id           BIGINT      NOT NULL COMMENT 'PK',
    order_id     BIGINT      NOT NULL COMMENT 'FK → order.id',
    company      VARCHAR(64) NOT NULL COMMENT '物流公司',
    tracking_no  VARCHAR(64) NOT NULL COMMENT '物流单号',
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SHIPPED/DELIVERED',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流信息';
