alter table reference_request add column declined_reason text
  check (declined_reason in ('DONT_KNOW_REQUESTER','TOO_BUSY','NOT_COMFORTABLE','OTHER'));
