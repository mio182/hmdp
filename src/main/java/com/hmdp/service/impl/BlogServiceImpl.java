package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private FollowServiceImpl followService;


    @Override
    public Result queryHotBlog(Integer current) {
         // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlog(blog);
            //也要查看是否被点赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        //这一步保证了未登录情况下直接返回
        if(user==null) return;

        Long uid = user.getId();



        //查看用户是否点赞过这个blog,将isLiked返回给前端，前端会渲染为红色
        String key= RedisConstants.BLOG_LIKED_KEY +blog.getId();
       /* Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, uid.toString());
        if(BooleanUtil.isTrue(isMember)){
            blog.setIsLike(true);
        }
       */
        Double score = stringRedisTemplate.opsForZSet().score(key, uid.toString());
        if(score!=null) blog.setIsLike(true);

    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("没有该id对应的用户");
        }
        queryBlog(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        //查看此user是否点赞过这个blog
        String userId = UserHolder.getUser().getId().toString();
        //设置redis中点赞过这个blog的用户id集合的key
        String key=RedisConstants.BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId);

        //若没有点赞过
        if(score==null){
            //访问数据库，让数据库like数目+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
            }
        }else{
            //若点赞过，则取消点赞
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId);
            }
        }



        return Result.ok();
    }

    /**
     * 点赞排行榜
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> usrIds = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY+id, 0, 4);
        if(usrIds==null || usrIds.size()==0){
            //没有人点赞则直接返回
            return Result.ok();
        }
        //将id转为Long类型
        List<Long> list = usrIds.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", list);

        //在数据库中根据id查询用户,根据传进来的id的顺序进行查询
        List<UserDTO> res = userService.query().in("id", list).last("ORDER BY FIELD(id," + join + ")").list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //将用户信息加密为UDTO类型并返回

        return Result.ok(res);
    }

    @Override
    public Result queryBlogOfUser(Long userId, Long current) {
        Page<Blog> page = new Page<>(current, 10);
        QueryWrapper<Blog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        List<Blog> records = blogMapper.selectPage(page, queryWrapper).getRecords();
        return Result.ok(records);

    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        //得先知道follower的id
        if (isSuccess) {
            Long followUserId = user.getId();
            List<Follow> followers = followService.query().eq("follow_user_id", followUserId).list();
            //使用写扩散模型，将博客推送到每个follower的收件箱
            for(Follow follower:followers){
                String key="inbox:"+follower.getUserId();
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }else{
            return Result.fail("blog保存失败");
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        //1.根据lastId和offset拿到对应的blogid,offset,minTime
        Long userId = UserHolder.getUser().getId();
        String key="inbox:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);

        List<Long> ids=new ArrayList<>();
        if(typedTuples==null || typedTuples.size()==0){
            return Result.ok();
        }
        offset=1;
        long minTime=0;
        for(ZSetOperations.TypedTuple<String> typedTuple:typedTuples){
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time = typedTuple.getScore().longValue();
            if(minTime==time){
                offset++;
            }else{
                minTime=time;
                offset=1;
            }
        }
        //查询blog
        String join = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();


        for (Blog blog : blogs) {
            queryBlog(blog);
            isBlogLiked(blog);
        }


        ScrollResult scrollResult = new ScrollResult(blogs, minTime, offset);
        return Result.ok(scrollResult);
    }


}
