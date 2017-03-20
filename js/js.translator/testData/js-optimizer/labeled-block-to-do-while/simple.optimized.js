var a = eval("true");

function box() {
    label: do {
        try {
            if (a) throw 1;
        }
        catch (e) {
            break label;
        }
        throw 2;
    } while (false);

    return 'OK';
};

function box2() {
    label: do {
        try {
            if (a) throw 1;
        }
        finally {
            break label;
        }
        throw 2;
    } while (false);

    return 'OK';
};

function box3() {
    label: do {
        try {
            if (a) throw 1;
        }
        finally {
            break label;
        }
    } while (false);

    return 'OK';
};