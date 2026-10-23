package de.brickforceaurora.server;

import de.brickforceaurora.server.net.login.ILoginHandler;

public interface IBrickForceServer {
    
    ILoginHandler loginHandler();
    
    default int port() {
        return 18890;
    }

    /**
     * @return the local address to listen on, {@code 127.0.0.1} keeps a player hosted server unreachable from the network
     */
    default String bindAddress() {
        return "0.0.0.0";
    }

}
