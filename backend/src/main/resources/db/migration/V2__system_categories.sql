-- ---------------------------------------------------------------------------
-- System categories and their default classification rules.
--
-- These are shared by every user (user_id IS NULL). A user can disable or reorder
-- them and add their own; the originals are never mutated.
--
-- UUIDs are fixed literals so that the rows are stable across environments and can be
-- referenced directly from tests and from the client's default ordering.
-- ---------------------------------------------------------------------------

INSERT INTO categories (id, user_id, key, name, description, color, icon, position, is_system) VALUES
    ('00000000-0000-4000-8000-000000000001', NULL, 'primary',    'Primary',    'Conversations with people, and anything that does not belong elsewhere.', '#4F46E5', 'inbox',        0, TRUE),
    ('00000000-0000-4000-8000-000000000002', NULL, 'social',     'Social',     'Messages from social networks and communities.',                          '#0EA5E9', 'people',       1, TRUE),
    ('00000000-0000-4000-8000-000000000003', NULL, 'promotions', 'Promotions', 'Deals, offers and marketing email.',                                      '#F59E0B', 'local_offer',  2, TRUE),
    ('00000000-0000-4000-8000-000000000004', NULL, 'updates',    'Updates',    'Notifications, confirmations and automated updates.',                     '#10B981', 'notifications',3, TRUE),
    ('00000000-0000-4000-8000-000000000005', NULL, 'forums',     'Forums',     'Mailing lists and discussion groups.',                                    '#8B5CF6', 'forum',        4, TRUE),
    ('00000000-0000-4000-8000-000000000006', NULL, 'finance',    'Finance',    'Statements, invoices, payments and banking.',                             '#059669', 'payments',     5, TRUE),
    ('00000000-0000-4000-8000-000000000007', NULL, 'travel',     'Travel',     'Bookings, itineraries and check-ins.',                                    '#EC4899', 'flight',       6, TRUE),
    ('00000000-0000-4000-8000-000000000008', NULL, 'receipts',   'Receipts',   'Orders, shipping and purchase receipts.',                                 '#6366F1', 'receipt',      7, TRUE);

-- ---------------------------------------------------------------------------
-- Default rules. `weight` is the score a match contributes; the categoriser picks the
-- highest-scoring category and records the normalised score as its confidence, so
-- specific signals (List-Unsubscribe, a bank's domain) outrank generic keywords.
-- ---------------------------------------------------------------------------

INSERT INTO category_rules (id, category_id, field, operation, value, weight) VALUES
    -- Social
    ('10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', 'SENDER_DOMAIN', 'ENDS_WITH', 'facebookmail.com', 60),
    ('10000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', 'SENDER_DOMAIN', 'ENDS_WITH', 'linkedin.com',     60),
    ('10000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000002', 'SENDER_DOMAIN', 'ENDS_WITH', 'x.com',            55),
    ('10000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000002', 'SENDER_DOMAIN', 'ENDS_WITH', 'instagram.com',    55),
    ('10000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-000000000002', 'SENDER_DOMAIN', 'ENDS_WITH', 'reddit.com',       50),
    ('10000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000002', 'SUBJECT',       'CONTAINS',  'tagged you',       40),
    ('10000000-0000-4000-8000-000000000007', '00000000-0000-4000-8000-000000000002', 'SUBJECT',       'CONTAINS',  'connection request', 40),

    -- Promotions
    ('10000000-0000-4000-8000-000000000010', '00000000-0000-4000-8000-000000000003', 'HEADER',  'CONTAINS', 'list-unsubscribe', 35),
    ('10000000-0000-4000-8000-000000000011', '00000000-0000-4000-8000-000000000003', 'SUBJECT', 'CONTAINS', '% off',            45),
    ('10000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000003', 'SUBJECT', 'CONTAINS', 'sale',             35),
    ('10000000-0000-4000-8000-000000000013', '00000000-0000-4000-8000-000000000003', 'SUBJECT', 'CONTAINS', 'deal',             30),
    ('10000000-0000-4000-8000-000000000014', '00000000-0000-4000-8000-000000000003', 'SUBJECT', 'CONTAINS', 'discount',         35),
    ('10000000-0000-4000-8000-000000000015', '00000000-0000-4000-8000-000000000003', 'SUBJECT', 'CONTAINS', 'limited time',     35),
    ('10000000-0000-4000-8000-000000000016', '00000000-0000-4000-8000-000000000003', 'SENDER',  'STARTS_WITH', 'newsletter@',   40),
    ('10000000-0000-4000-8000-000000000017', '00000000-0000-4000-8000-000000000003', 'SENDER',  'STARTS_WITH', 'marketing@',    45),
    ('10000000-0000-4000-8000-000000000018', '00000000-0000-4000-8000-000000000003', 'SENDER',  'STARTS_WITH', 'promo',         45),

    -- Updates
    ('10000000-0000-4000-8000-000000000020', '00000000-0000-4000-8000-000000000004', 'SENDER',  'STARTS_WITH', 'no-reply',      40),
    ('10000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000004', 'SENDER',  'STARTS_WITH', 'noreply',       40),
    ('10000000-0000-4000-8000-000000000022', '00000000-0000-4000-8000-000000000004', 'SENDER',  'STARTS_WITH', 'notifications', 45),
    ('10000000-0000-4000-8000-000000000023', '00000000-0000-4000-8000-000000000004', 'SUBJECT', 'CONTAINS',    'verify your',   50),
    ('10000000-0000-4000-8000-000000000024', '00000000-0000-4000-8000-000000000004', 'SUBJECT', 'CONTAINS',    'security alert', 55),
    ('10000000-0000-4000-8000-000000000025', '00000000-0000-4000-8000-000000000004', 'SUBJECT', 'CONTAINS',    'password',      45),
    ('10000000-0000-4000-8000-000000000026', '00000000-0000-4000-8000-000000000004', 'SUBJECT', 'CONTAINS',    'sign-in',       40),

    -- Forums
    ('10000000-0000-4000-8000-000000000030', '00000000-0000-4000-8000-000000000005', 'LIST_ID', 'CONTAINS', '.',                50),
    ('10000000-0000-4000-8000-000000000031', '00000000-0000-4000-8000-000000000005', 'SENDER',  'CONTAINS', 'groups.google.com', 60),
    ('10000000-0000-4000-8000-000000000032', '00000000-0000-4000-8000-000000000005', 'SUBJECT', 'CONTAINS', 're: [',            35),
    ('10000000-0000-4000-8000-000000000033', '00000000-0000-4000-8000-000000000005', 'SENDER_DOMAIN', 'ENDS_WITH', 'discourse.org', 55),

    -- Finance
    ('10000000-0000-4000-8000-000000000040', '00000000-0000-4000-8000-000000000006', 'SUBJECT', 'CONTAINS', 'invoice',           60),
    ('10000000-0000-4000-8000-000000000041', '00000000-0000-4000-8000-000000000006', 'SUBJECT', 'CONTAINS', 'statement',         55),
    ('10000000-0000-4000-8000-000000000042', '00000000-0000-4000-8000-000000000006', 'SUBJECT', 'CONTAINS', 'payment',           50),
    ('10000000-0000-4000-8000-000000000043', '00000000-0000-4000-8000-000000000006', 'SUBJECT', 'CONTAINS', 'transaction',       50),
    ('10000000-0000-4000-8000-000000000044', '00000000-0000-4000-8000-000000000006', 'SENDER',  'CONTAINS', 'billing@',          55),
    ('10000000-0000-4000-8000-000000000045', '00000000-0000-4000-8000-000000000006', 'SENDER_DOMAIN', 'ENDS_WITH', 'stripe.com', 60),

    -- Travel
    ('10000000-0000-4000-8000-000000000050', '00000000-0000-4000-8000-000000000007', 'SUBJECT', 'CONTAINS', 'itinerary',         65),
    ('10000000-0000-4000-8000-000000000051', '00000000-0000-4000-8000-000000000007', 'SUBJECT', 'CONTAINS', 'boarding pass',     70),
    ('10000000-0000-4000-8000-000000000052', '00000000-0000-4000-8000-000000000007', 'SUBJECT', 'CONTAINS', 'check-in',          50),
    ('10000000-0000-4000-8000-000000000053', '00000000-0000-4000-8000-000000000007', 'SUBJECT', 'CONTAINS', 'your booking',      60),
    ('10000000-0000-4000-8000-000000000054', '00000000-0000-4000-8000-000000000007', 'SUBJECT', 'CONTAINS', 'reservation',       55),
    ('10000000-0000-4000-8000-000000000055', '00000000-0000-4000-8000-000000000007', 'SENDER_DOMAIN', 'ENDS_WITH', 'booking.com', 60),

    -- Receipts
    ('10000000-0000-4000-8000-000000000060', '00000000-0000-4000-8000-000000000008', 'SUBJECT', 'CONTAINS', 'your order',        65),
    ('10000000-0000-4000-8000-000000000061', '00000000-0000-4000-8000-000000000008', 'SUBJECT', 'CONTAINS', 'order confirmation', 70),
    ('10000000-0000-4000-8000-000000000062', '00000000-0000-4000-8000-000000000008', 'SUBJECT', 'CONTAINS', 'has shipped',       65),
    ('10000000-0000-4000-8000-000000000063', '00000000-0000-4000-8000-000000000008', 'SUBJECT', 'CONTAINS', 'receipt',           60),
    ('10000000-0000-4000-8000-000000000064', '00000000-0000-4000-8000-000000000008', 'SUBJECT', 'CONTAINS', 'delivered',         45);
