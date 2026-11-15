-- Run this together with the release that no longer contains posts (v6.7+).
-- Do NOT run while v6.6 or older is still in use: those versions call these functions.
create or replace function public.owner_stats() returns jsonb language plpgsql security definer set search_path to '' as $$
declare r jsonb;
begin
  if not private.is_owner() then raise exception 'not owner'; end if;
  select jsonb_build_object(
    'users',(select count(*) from public.profiles),
    'active24',(select count(*) from public.profiles where last_seen > now()-interval '24 hours'),
    'active7',(select count(*) from public.profiles where last_seen > now()-interval '7 days'),
    'signups',(select coalesce(jsonb_agg(jsonb_build_object('d',d,'n',n) order by d),'[]'::jsonb) from (select g::date d,(select count(*) from public.profiles p where p.created_at::date=g::date) n from generate_series(now()-interval '13 days',now(),interval '1 day') g) x)
  ) into r;
  return r;
end $$;
drop function if exists public.add_comment, public.create_post, public.delete_post, public.edit_post, public.feed, public.feed_following,
  public.follow_list, public.follow_requests_list, public.follow_respond, public.get_post, public.liked_posts, public.my_notifs,
  public.my_saved_ids, public.notif_unread, public.notifs_read, public.owner_clear_reports, public.owner_reports, public.post_comments,
  public.profile_stats, public.report_post, public.saved_posts, public.set_post_days, public.suggest_people, public.tag_posts,
  public.toggle_comment_like, public.toggle_follow, public.toggle_like, public.toggle_save, public.user_posts, public.view_posts;
drop table if exists public.comment_likes, public.post_comments, public.post_likes, public.post_saves, public.post_views,
  public.post_reports, public.posts, public.notifs, public.follow_requests, public.follows cascade;
