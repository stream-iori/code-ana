package sample;

public class UserService {
    public String display(User user) {
        return user.getName() + ":" + user.isActive();
    }
}
