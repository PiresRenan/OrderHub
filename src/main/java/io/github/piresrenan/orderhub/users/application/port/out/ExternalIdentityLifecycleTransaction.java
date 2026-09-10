package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.function.Supplier;

public interface ExternalIdentityLifecycleTransaction {
    <T> T execute(Supplier<T> work);
}
