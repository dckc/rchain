'''RChain node client

Usage:
  python RChain.py -w
  python RChain.py contract1.rho
  python RChain.py -c 'new x in { x!(1 + 1) }'

We assume the RChain node is running and that it is listening on port
50000.

The -w option starts a simple web UI.

The output from -c or X.rho should be something like:

Storage Contents:
 @{15a23988-03df-4835-9c55-fb9fbf843a47}!(2) |
 for( x0, x1 <= @{\"stdoutAck\"} ) { Nil } |
 for( x0 <= @{\"stdout\"} ) { Nil } |
 for( x0, x1 <= @{\"stderrAck\"} ) { Nil } |
 for( x0 <= @{\"stderr\"} ) { Nil }"

'''

from __future__ import print_function

from tornado.web import Application, RequestHandler

# cribbed from https://grpc.io/docs/tutorials/basic/python.html
import rnode_pb2 as rnode
import rnode_pb2_grpc


def main(argv, stdout,
         IOLoop,
         insecure_channel,
         web_port=8888,
         host='127.0.0.1',
         port=50000):
    channel = insecure_channel('%s:%s' % (host, port))
    replCh = rnode_pb2_grpc.ReplStub(channel)
    diagCh = rnode_pb2_grpc.DiagnosticsStub(channel)

    if '-w' in argv:
        app, url = RNodeUI.make_app(web_port, diagCh, replCh)
        print("rnode web UI at %s" % url, file=stdout)
        IOLoop.current().start()
    elif '-c' in argv:
        line = argv[-1]
        req = rnode.CmdRequest(line=line)
        output = replCh.Run(req).output
    else:
        fileName = argv[1]
        req = rnode.EvalRequest(fileName=fileName)
        output = replCh.Eval(req).output
    print(output, file=stdout)


# issue: https://github.com/grpc/grpc/wiki/Integration-with-tornado-(python)

class RNodeUI(RequestHandler):
    def initialize(self, diagCh, replCh):
        self.__diagCh = diagCh
        self.__replCh = replCh
        self.rholang_code = ''
        self.rspace_contents = ''

    markup = '''
    <!doctype html>
    <html>
    <head><title>rnode diagnostics</title></head>
    <body>
    <h1>rnode</h1>
    <address>
    <b>pre-release</b> for the
    <a href="https://developer.rchain.coop/">RChain Developer</a>
    community
    </address>

    <h2>Peers</h2>
    <ul>
    <!-- PART -->
    </ul>

    <h2>Rholang and RSpace</h2>
    <form action="" method="post">
    <textarea name="rho1" cols="40" rows="10"><!-- PART --></textarea>
    <br />
    <input type="submit" value="Run" />
    </form>

    <pre id="store">
    <!-- PART -->
    </pre>
    </body>
    </html>
    '''

    @classmethod
    def make_app(cls, port, diagCh, replCh):
        app = Application([
            (r"/", cls, dict(diagCh=diagCh, replCh=replCh)),
        ])
        app.listen(port)
        url = "http://0.0.0.0:%s" % port
        return app, url

    def get(self):
        mtop1, mform2, mf3, m4 = self.markup.split('<!-- PART -->')
        self.write(mtop1)

        peers = self.__diagCh.ListPeers(rnode.ListPeersRequest()).peers
        for peer in peers:
            self.write("<li>%s</li>" % peer)

        self.write(mform2)
        self.write(self.rholang_code)
        self.write(mf3)
        self.write(self.rspace_contents)
        self.write(m4)

    def post(self):
        replCh = self.__replCh
        line = self.get_body_argument('rho1')
        self.rholang_code = line

        req = rnode.CmdRequest(line=line)
        self.rspace_contents = replCh.Run(req).output.replace(
            ' | ', ' |\n')
        self.get()


if __name__ == '__main__':
    def _script():
        from sys import argv, stdout

        from tornado.ioloop import IOLoop
        from grpc import insecure_channel

        main(argv, stdout, IOLoop, insecure_channel)
    _script()
