import java.util.*;
import java.io.*;


public class StudentNetworkSimulator extends NetworkSimulator
{
    /*
     * Predefined Constants (static member variables):
     *
     *   int MAXDATASIZE : the maximum size of the Message data and
     *                     Packet payload
     *
     *   int A           : a predefined integer that represents entity A
     *   int B           : a predefined integer that represents entity B
     *
     * Predefined Member Methods:
     *
     *  void stopTimer(int entity):
     *       Stops the timer running at "entity" [A or B]
     *  void startTimer(int entity, double increment):
     *       Starts a timer running at "entity" [A or B], which will expire in
     *       "increment" time units, causing the interrupt handler to be
     *       called.  You should only call this with A.
     *  void toLayer3(int callingEntity, Packet p)
     *       Puts the packet "p" into the network from "callingEntity" [A or B]
     *  void toLayer5(String dataSent)
     *       Passes "dataSent" up to layer 5
     *  double getTime()
     *       Returns the current time in the simulator.  Might be useful for
     *       debugging.
     *  int getTraceLevel()
     *       Returns TraceLevel
     *  void printEventList()
     *       Prints the current event list to stdout.  Might be useful for
     *       debugging, but probably not.
     *
     *
     *  Predefined Classes:
     *
     *  Message: Used to encapsulate a message coming from layer 5
     *    Constructor:
     *      Message(String inputData):
     *          creates a new Message containing "inputData"
     *    Methods:
     *      boolean setData(String inputData):
     *          sets an existing Message's data to "inputData"
     *          returns true on success, false otherwise
     *      String getData():
     *          returns the data contained in the message
     *  Packet: Used to encapsulate a packet
     *    Constructors:
     *      Packet (Packet p):
     *          creates a new Packet that is a copy of "p"
     *      Packet (int seq, int ack, int check, String newPayload)
     *          creates a new Packet with a sequence field of "seq", an
     *          ack field of "ack", a checksum field of "check", and a
     *          payload of "newPayload"
     *      Packet (int seq, int ack, int check)
     *          chreate a new Packet with a sequence field of "seq", an
     *          ack field of "ack", a checksum field of "check", and
     *          an empty payload
     *    Methods:
     *      boolean setSeqnum(int n)
     *          sets the Packet's sequence field to "n"
     *          returns true on success, false otherwise
     *      boolean setAcknum(int n)
     *          sets the Packet's ack field to "n"
     *          returns true on success, false otherwise
     *      boolean setChecksum(int n)
     *          sets the Packet's checksum to "n"
     *          returns true on success, false otherwise
     *      boolean setPayload(String newPayload)
     *          sets the Packet's payload to "newPayload"
     *          returns true on success, false otherwise
     *      int getSeqnum()
     *          returns the contents of the Packet's sequence field
     *      int getAcknum()
     *          returns the contents of the Packet's ack field
     *      int getChecksum()
     *          returns the checksum of the Packet
     *      int getPayload()
     *          returns the Packet's payload
     *
     */

    /*   Please use the following variables in your routines.
     *   int WindowSize  : the window size
     *   double RxmtInterval   : the retransmission timeout
     *   int LimitSeqNo  : when sequence number reaches this value, it wraps around
     */

    public static final int FirstSeqNo = 0;
    private int WindowSize;
    private double RxmtInterval;
    private int LimitSeqNo;

    // Add any necessary class variables here. Remember, you cannot use
    // these variables to send messages error free! They can only hold
    // state information for A or B.
    // Also add any necessary methods (e.g. checksum of a String)

    //A params
    private int A_oringin = 0;
    private int A_retrans = 0;
    private int A_windowHead, A_nextseq, A_last;
    private final List<Packet> A_buffer = new ArrayList<>();
    private final List<Integer> ack_buffer = new ArrayList<>();


    //B params
    private int B_deliver5 = 0;
    private int B_acknum = 0;

    private int B_next;
    private int[] bSack = {-1, -1, -1, -1, -1};
    private final List<Packet> B_buffer = new ArrayList<>();

    //store params and maps
    private int corruptNum = 0;
    private final Map<Integer,Double> cacheRTT = new HashMap<>();
    private int rttNum = 0;
    private double rtt = 0.0;
    private final Map<Integer,Double> cacheComm = new HashMap<>();
    private double commNum = 0;
    private double comm = 0.0;


    // This is the constructor. Don't touch!
    public StudentNetworkSimulator(int numMessages, double loss, double corrupt, double avgDelay, int trace, int seed,
                                   int winsize, double delay) {
        super(numMessages, loss, corrupt, avgDelay, trace, seed);
        WindowSize = winsize;
        LimitSeqNo = winsize +1; //modify to winsize+1 to fit GBN
        //SR default:
        //LimitSeqNo = winsize*2;
        // set appropriately; assumes SR here!
        RxmtInterval = delay;
    }

    // This routine will be called whenever the upper layer at the sender [A]
    // has a message to send. It is the job of your protocol to insure that
    // the data in such a message is delivered in-order, and correctly, to
    // the receiving upper layer.
    protected void aOutput(Message message) {
        A_nextseq = A_last % LimitSeqNo;
        Packet p = new Packet(A_nextseq, 0, -1, message.getData(),new int[5]);
        p.setChecksum(checkSum(p));
        A_buffer.add(p);
        ack_buffer.add(0);
        A_checkBuffer();
    }

    // This routine will be called whenever a packet sent from the B-side
    // (i.e. as a result of a toLayer3() being done by a B-side procedure)
    // arrives at the A-side. "packet" is the (possibly corrupted) packet
    // sent from the B-side.
    protected void aInput(Packet packet) {
        if(checkSum(packet) == packet.getChecksum()){
            int currHead = A_windowHead % LimitSeqNo;
            for (int j : packet.getSack()) {
                if (j != -1) ack_buffer.set(A_windowHead+(j-currHead+LimitSeqNo)%LimitSeqNo, 2);
            }
            int pSeq = packet.getSeqnum();
            rttNum++;
            if(pSeq==currHead) return;
            stopTimer(A);
            int last_send_base = A_windowHead;
            A_windowHead += (pSeq - currHead + LimitSeqNo)%LimitSeqNo;//cover case that packet seqnum<currHead
            for(int i=last_send_base;i<A_windowHead && i<ack_buffer.size();i++){
                    double tmptime = cacheRTT.get(A_windowHead-1);
                    if(tmptime != -1.0){
                        rtt += getTime() - tmptime;
                        cacheRTT.put(last_send_base,-1.0);
                    }
                    comm += getTime() - cacheComm.get(last_send_base);
                    commNum++;
                }
        }
        else{
            //packet corrupted
            corruptNum++;
        }
    }

    // This routine will be called when A's timer expires (thus generating a
    // timer interrupt). You'll probably want to use this routine to control
    // the retransmission of packets. See startTimer() and stopTimer(), above,
    // for how the timer is started and stopped.
    protected void aTimerInterrupt(){
        for(int i=A_windowHead;i<A_last;i++){
            if(ack_buffer.get(i)!=2){
                if(ack_buffer.get(i)==0){
                    //it's a new packet in window which has not yet been sent
                    //update global variables
                    A_oringin++;
                    rttNum++;
                    commNum++;
                    cacheComm.put(i,getTime());
                }else{
                    //it's a packet has been sent before
                    //undate A_resend variable
                    A_retrans++;
                }
                //send the packet with its seqnum
                A_sendpkt(i);
            }
        }
    }

    // This routine will be called once, before any of your other A-side
    // routines are called. It can be used to do any required
    // initialization (e.g. of member variables you add to control the state
    // of entity A).
    protected void aInit() {
        A_windowHead = 0;
        A_nextseq = 0;
        A_last = 0;
        LimitSeqNo = WindowSize + 1;
    }

    // This routine will be called whenever a packet sent from the A-side
    // (i.e. as a result of a toLayer3() being done by an A-side procedure)
    // arrives at the B-side. "packet" is the (possibly corrupted) packet
    // sent from the A-side.
    protected void bInput(Packet packet) {
        if (packet.getChecksum() != checkSum(packet)) {
            //got corrupt packet
            corruptNum++;
            return;
        }
        int pSeq=packet.getSeqnum();

        if ((pSeq < B_next && (B_next - pSeq <= WindowSize))
                || (pSeq > B_next && (pSeq - B_next>= WindowSize))) {
            B_sendpkt(B_next, bSack);
        } else if ((pSeq != B_next) && (B_buffer.size() == 5)) {
            B_sendpkt(B_next, bSack);
        } else if (pSeq == B_next) {
                // if the sack is not full
                //move next B ptr
                B_next = (B_next + 1) % LimitSeqNo;
                toLayer5(packet.getPayload());
                B_deliver5 ++;
                //B buffer empty, just send
                if (B_buffer.size() != 0) {
                    B_sendpkt(B_next, bSack);
                    while (B_buffer.size() != 0 && B_buffer.get(0).getSeqnum() == B_next) {
                        toLayer5(packet.getPayload());
                        B_deliver5++;
                        B_next = (B_next + 1) % LimitSeqNo;
                        B_buffer.remove(0);
                    }
                    if (B_buffer.size() == 0) {
                        Arrays.fill(bSack, -1);
                    } else {
                        B_setSack();
                    }
                }
                B_sendpkt(B_next, bSack);
        } else {
            int length = B_buffer.size();
            for (int i = 0; i < length; i++) {

                if (pSeq == B_buffer.get(i).getSeqnum()) {
                    // packet is duplicate
                    B_sendpkt(B_next, bSack);
                    return;
                }
                if (pSeq > B_next) {
                   if ((B_buffer.get(i).getSeqnum() > B_next && pSeq < B_buffer.get(i).getSeqnum())
                            || B_buffer.get(i).getSeqnum() < B_next) {
                       B_buffer.add(i, packet);
                       break;
                   }
                } else if (pSeq < B_next){
                    if(B_buffer.get(i).getSeqnum() < B_next|| pSeq < B_buffer.get(i).getSeqnum()){
                        B_buffer.add(i, packet);
                        break;
                    }
                }

            }
            if (B_buffer.size() == length) {
                B_buffer.add(packet);
            }
            B_setSack();
            B_sendpkt(B_next, bSack);
        }

    }
    // This routine will be called once, before any of your other B-side
    // routines are called. It can be used to do any required
    // initialization (e.g. of member variables you add to control the state
    // of entity B).
    protected void bInit() {
        B_next = 0;
    }

    // Use to print final statistics
    protected void Simulation_done() {
        // TO PRINT THE STATISTICS, FILL IN THE DETAILS BY PUTTING VARIBALE NAMES. DO
        // NOT CHANGE THE FORMAT OF PRINTED OUTPUT


        System.out.println("\n\n===============STATISTICS=======================");
        System.out.println("Number of original packets transmitted by A:" + A_oringin);
        System.out.println("Number of retransmissions by A:" + A_retrans);
        System.out.println("Number of data packets delivered to layer 5 at B:" + B_deliver5);
        System.out.println("Number of ACK packets sent by B:" + B_acknum);
        System.out.println("Number of corrupted packets:" + corruptNum);
        double  lostR = A_retrans > corruptNum ?(double)(A_retrans - corruptNum)/(double)((A_oringin+A_retrans)+B_acknum):0;
        System.out.println("Ratio of lost packets:" + lostR);
        double  corruptR = (double)corruptNum / (double)((A_oringin+A_retrans)+ B_acknum-(A_retrans-corruptNum));
        System.out.println("Ratio of corrupted packets:" + corruptR);
        System.out.println("Average RTT:" + rtt/(double) rttNum);
        System.out.println("Average communication time:" + comm/(double)commNum);
        System.out.println("==================================================");

        // PRINT YOUR OWN STATISTIC HERE TO CHECK THE CORRECTNESS OF YOUR PROGRAM
        // EXAMPLE GIVEN BELOW
        // System.out.println("Example statistic you want to check e.g. number of ACK packets received by A :" + "<YourVariableHere>");
    }

    private void A_checkBuffer(){
        for (int i = A_windowHead; i < A_buffer.size() && i < A_windowHead + WindowSize; i++) {
            if (A_buffer.get(i) != null && ack_buffer.get(i) == 0) {
                A_sendpkt(i);
                A_oringin++;
                //for first time send
                //put time to comm map and set pkt ack=1
                ack_buffer.set(i,1);
                cacheComm.put(i,getTime());
            }
            A_last=i+1;
        }

    }
    private void B_setSack(){
        for (int i = 0; i < 5; i++) {
            if (i < B_buffer.size()) {
                bSack[i] = B_buffer.get(i).getSeqnum();
            }
            bSack[i] = -1;
        }
    }
    private void A_sendpkt(int seqnum) {
        toLayer3(A, A_buffer.get(seqnum));
        cacheRTT.put(seqnum,getTime());
        stopTimer(A);
        startTimer(A, RxmtInterval);
    }

    private void B_sendpkt(int seqnum, int[] sack) {
        Packet p = new Packet(seqnum, 1, seqnum + 1, sack);
        toLayer3(B, p);
        B_acknum++;
    }

    private int checkSum(Packet packet) {
        int sum = packet.getSeqnum() + packet.getAcknum();
        for (char c : packet.getPayload().toCharArray()) {
            sum += Character.getNumericValue(c);
        }
        return sum;
    }

}